// Platform layer that lets Minecraft drive doomgeneric from another process.
//
// Video, sound effect events and music state go into a memory-mapped file
// whose path is given by the DOOMCRAFT_SHM environment variable. Input comes
// in on stdin as 4-byte messages. When stdin closes, Minecraft has gone away,
// so the engine exits instead of running on as an orphan.

#include "doomkeys.h"
#include "doomgeneric.h"
#include "d_event.h"
#include "i_sound.h"
#include "w_wad.h"
#include "z_zone.h"

#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/time.h>
#include <unistd.h>

// Shared file layout. DoomEngine.java mirrors these offsets.
#define SHM_MAGIC          0x4D4F4F44u  // "DOOM" little endian
#define OFF_MAGIC          0
#define OFF_WIDTH          4
#define OFF_HEIGHT         8
#define OFF_FRAME          12
#define OFF_SFX_WRITE      16
#define OFF_MUS_SONG       20
#define OFF_MUS_LENGTH     24
#define OFF_MUS_PLAYING    28
#define OFF_MUS_LOOPING    32
#define OFF_MUS_PAUSED     36
#define OFF_MUS_VOLUME     40
#define OFF_HEARTBEAT      44
#define OFF_SFX_RING       64
#define SFX_RING_SIZE      256
#define SFX_EVENT_SIZE     32
#define OFF_FRAMES         16384
#define FRAME_BYTES        (DOOMGENERIC_RESX * DOOMGENERIC_RESY * 4)
#define OFF_MUSIC          (OFF_FRAMES + 2 * FRAME_BYTES)
#define MUSIC_MAX          (256 * 1024)
#define SHM_SIZE           (OFF_MUSIC + MUSIC_MAX)

enum { SFX_START = 1, SFX_STOP = 2, SFX_UPDATE = 3 };
enum { MSG_KEY = 1, MSG_MOUSE = 2, MSG_QUIT = 3 };

static uint8_t *shm;
static uint32_t frame_count;
static uint32_t sfx_write;

static inline _Atomic uint32_t *shm_u32(int off)
{
    return (_Atomic uint32_t *)(shm + off);
}

static void put(int off, uint32_t v)
{
    atomic_store_explicit(shm_u32(off), v, memory_order_release);
}

// ---------------------------------------------------------------- video

void DG_Init(void)
{
    const char *path = getenv("DOOMCRAFT_SHM");
    if (path == NULL)
    {
        fprintf(stderr, "DOOMCRAFT_SHM is not set\n");
        exit(2);
    }

    int fd = open(path, O_RDWR | O_CREAT, 0600);
    if (fd < 0 || ftruncate(fd, SHM_SIZE) != 0)
    {
        perror("doomcraft: opening shared file");
        exit(2);
    }

    shm = mmap(NULL, SHM_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (shm == MAP_FAILED)
    {
        perror("doomcraft: mmap");
        exit(2);
    }

    put(OFF_WIDTH, DOOMGENERIC_RESX);
    put(OFF_HEIGHT, DOOMGENERIC_RESY);
    put(OFF_MUS_VOLUME, 100);
    put(OFF_MAGIC, SHM_MAGIC);

    int flags = fcntl(0, F_GETFL, 0);
    fcntl(0, F_SETFL, flags | O_NONBLOCK);
}

void DG_DrawFrame(void)
{
    // Double buffered: write the slot the reader is not on, then publish it.
    uint32_t next = frame_count + 1;
    memcpy(shm + OFF_FRAMES + (next & 1) * FRAME_BYTES, DG_ScreenBuffer, FRAME_BYTES);
    frame_count = next;
    put(OFF_FRAME, frame_count);
}

void DG_SleepMs(uint32_t ms)
{
    usleep(ms * 1000);
}

uint32_t DG_GetTicksMs(void)
{
    struct timeval tp;
    gettimeofday(&tp, NULL);
    return (uint32_t)(tp.tv_sec * 1000 + tp.tv_usec / 1000);
}

void DG_SetWindowTitle(const char *title)
{
    (void)title;
}

// ---------------------------------------------------------------- input

static uint8_t in_buf[4096];
static size_t in_len;

#define KEYQUEUE_SIZE 64
static uint16_t key_queue[KEYQUEUE_SIZE];
static unsigned key_read, key_write;

static void pump_input(void)
{
    for (;;)
    {
        ssize_t n = read(0, in_buf + in_len, sizeof(in_buf) - in_len);
        if (n == 0)
        {
            // Minecraft closed our stdin.
            exit(0);
        }
        if (n < 0)
        {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)
                break;
            exit(0);
        }
        in_len += (size_t)n;
        if (in_len < sizeof(in_buf))
            break;
    }

    size_t i = 0;
    for (; i + 4 <= in_len; i += 4)
    {
        uint8_t *m = in_buf + i;
        switch (m[0])
        {
        case MSG_KEY:
            key_queue[key_write % KEYQUEUE_SIZE] = (uint16_t)((m[1] ? 1 : 0) << 8 | m[2]);
            key_write++;
            break;
        case MSG_MOUSE:
        {
            event_t ev;
            ev.type = ev_mouse;
            ev.data1 = m[1];
            ev.data2 = (int8_t)m[2] * 8;
            ev.data3 = 0;
            (void)m[3];
            D_PostEvent(&ev);
            break;
        }
        case MSG_QUIT:
            exit(0);
        }
    }
    memmove(in_buf, in_buf + i, in_len - i);
    in_len -= i;
}

int DG_GetKey(int *pressed, unsigned char *key)
{
    if (key_read == key_write)
    {
        pump_input();
        put(OFF_HEARTBEAT, DG_GetTicksMs());
    }
    if (key_read == key_write)
        return 0;

    uint16_t data = key_queue[key_read % KEYQUEUE_SIZE];
    key_read++;
    *pressed = data >> 8;
    *key = data & 0xff;
    return 1;
}

// ---------------------------------------------------------------- sound

// Doom asks whether a channel is still busy, so remember when each one ends.
#define NUM_CHANNELS 16
static uint32_t channel_end_ms[NUM_CHANNELS];
static boolean sfx_prefix;

static void push_sfx(uint32_t type, int channel, int vol, int sep, const char *name)
{
    uint8_t *e = shm + OFF_SFX_RING + (sfx_write % SFX_RING_SIZE) * SFX_EVENT_SIZE;
    memset(e, 0, SFX_EVENT_SIZE);
    memcpy(e + 0, &type, 4);
    memcpy(e + 4, &channel, 4);
    memcpy(e + 8, &vol, 4);
    memcpy(e + 12, &sep, 4);
    if (name != NULL)
        strncpy((char *)e + 16, name, 15);
    sfx_write++;
    put(OFF_SFX_WRITE, sfx_write);
}

static boolean DG_SoundInit(boolean use_sfx_prefix)
{
    sfx_prefix = use_sfx_prefix;
    return shm != NULL;
}

static void DG_SoundShutdown(void)
{
}

static int DG_GetSfxLumpNum(sfxinfo_t *sfx)
{
    char namebuf[16];
    if (sfx->link != NULL)
        sfx = sfx->link;
    snprintf(namebuf, sizeof(namebuf), sfx_prefix ? "ds%s" : "%s", sfx->name);
    return W_GetNumForName(namebuf);
}

static void DG_SoundUpdate(void)
{
}

static void DG_UpdateSoundParams(int channel, int vol, int sep)
{
    push_sfx(SFX_UPDATE, channel, vol, sep, NULL);
}

static int DG_StartSound(sfxinfo_t *sfx, int channel, int vol, int sep)
{
    if (channel < 0 || channel >= NUM_CHANNELS)
        return -1;

    if (sfx->lumpnum < 0)
        sfx->lumpnum = DG_GetSfxLumpNum(sfx);

    // DMX header: format(2) rate(2) samples(4), then 8-bit unsigned PCM.
    int len = W_LumpLength(sfx->lumpnum);
    const uint8_t *data = W_CacheLumpNum(sfx->lumpnum, PU_STATIC);
    uint32_t duration = 0;
    if (len > 8 && data[0] == 3 && data[1] == 0)
    {
        unsigned rate = data[2] | (data[3] << 8);
        unsigned samples = data[4] | (data[5] << 8) | (data[6] << 16) | ((unsigned)data[7] << 24);
        if (rate > 0)
            duration = (uint32_t)((uint64_t)samples * 1000 / rate);
    }
    W_ReleaseLumpNum(sfx->lumpnum);

    char lumpname[9];
    memcpy(lumpname, lumpinfo[sfx->lumpnum].name, 8);
    lumpname[8] = '\0';

    channel_end_ms[channel] = DG_GetTicksMs() + duration;
    push_sfx(SFX_START, channel, vol, sep, lumpname);
    return channel;
}

static void DG_StopSound(int channel)
{
    if (channel >= 0 && channel < NUM_CHANNELS)
        channel_end_ms[channel] = 0;
    push_sfx(SFX_STOP, channel, 0, 0, NULL);
}

static boolean DG_SoundIsPlaying(int channel)
{
    if (channel < 0 || channel >= NUM_CHANNELS)
        return false;
    return (int32_t)(channel_end_ms[channel] - DG_GetTicksMs()) > 0;
}

static void DG_CacheSounds(sfxinfo_t *sounds, int num_sounds)
{
    (void)sounds;
    (void)num_sounds;
}

static snddevice_t sound_devices[] =
{
    SNDDEVICE_SB, SNDDEVICE_PAS, SNDDEVICE_GUS,
    SNDDEVICE_WAVEBLASTER, SNDDEVICE_SOUNDCANVAS, SNDDEVICE_AWE32,
};

sound_module_t DG_sound_module =
{
    sound_devices,
    sizeof(sound_devices) / sizeof(*sound_devices),
    DG_SoundInit,
    DG_SoundShutdown,
    DG_GetSfxLumpNum,
    DG_SoundUpdate,
    DG_UpdateSoundParams,
    DG_StartSound,
    DG_StopSound,
    DG_SoundIsPlaying,
    DG_CacheSounds,
};

// ---------------------------------------------------------------- music

// Music is published as state. Java converts the MUS data to MIDI itself.
static uint32_t song_id;

static boolean DG_MusicInit(void)
{
    return true;
}

static void DG_MusicShutdown(void)
{
    put(OFF_MUS_PLAYING, 0);
}

static void DG_SetMusicVolume(int volume)
{
    put(OFF_MUS_VOLUME, (uint32_t)volume);
}

static void DG_PauseMusic(void)
{
    put(OFF_MUS_PAUSED, 1);
}

static void DG_ResumeMusic(void)
{
    put(OFF_MUS_PAUSED, 0);
}

static void *DG_RegisterSong(void *data, int len)
{
    if (len > MUSIC_MAX)
        len = MUSIC_MAX;
    put(OFF_MUS_PLAYING, 0);
    memcpy(shm + OFF_MUSIC, data, (size_t)len);
    put(OFF_MUS_LENGTH, (uint32_t)len);
    song_id++;
    put(OFF_MUS_SONG, song_id);
    return (void *)(uintptr_t)song_id;
}

static void DG_UnRegisterSong(void *handle)
{
    (void)handle;
}

static void DG_PlaySong(void *handle, boolean looping)
{
    (void)handle;
    put(OFF_MUS_LOOPING, looping ? 1 : 0);
    put(OFF_MUS_PAUSED, 0);
    put(OFF_MUS_PLAYING, 1);
}

static void DG_StopSong(void)
{
    put(OFF_MUS_PLAYING, 0);
}

static boolean DG_MusicIsPlaying(void)
{
    return atomic_load(shm_u32(OFF_MUS_PLAYING)) != 0;
}

static snddevice_t music_devices[] =
{
    SNDDEVICE_PAS, SNDDEVICE_GUS, SNDDEVICE_WAVEBLASTER,
    SNDDEVICE_SOUNDCANVAS, SNDDEVICE_GENMIDI, SNDDEVICE_AWE32,
};

music_module_t DG_music_module =
{
    music_devices,
    sizeof(music_devices) / sizeof(*music_devices),
    DG_MusicInit,
    DG_MusicShutdown,
    DG_SetMusicVolume,
    DG_PauseMusic,
    DG_ResumeMusic,
    DG_RegisterSong,
    DG_UnRegisterSong,
    DG_PlaySong,
    DG_StopSong,
    DG_MusicIsPlaying,
    NULL,
};

// Referenced by the config code when sound is compiled in.
int use_libsamplerate = 0;
float libsamplerate_scale = 0.65f;

void I_InitTimidityConfig(void)
{
}

// ---------------------------------------------------------------- main

int main(int argc, char **argv)
{
    doomgeneric_Create(argc, argv);
    for (;;)
        doomgeneric_Tick();
    return 0;
}
