# SyrRadio

One shared radio for a Minecraft server. Paste a link to a track — Yandex Music,
YouTube, SoundCloud, Bandcamp or anything else [yt-dlp](https://github.com/yt-dlp/yt-dlp)
understands — and it plays for everyone on the server at the same time.

Forge 1.20.1. The mod is needed on the server and on every player who wants to
listen; players without it can still join and simply don't hear the radio.

## What it does

- **One playlist for the whole server.** Played tracks stay in the list (grey),
  the current one is highlighted, upcoming ones are in normal colour. Anyone can
  add, reorder, play or remove tracks.
- **Player controls:** shuffle, previous, pause, next, repeat (off → whole list →
  this track). Pause and modes are shared by everyone.
- **Seek** by dragging the knob on the progress bar.
- **Drag to reorder** any track, including played ones. Every row has ▶ (play
  now) and ✕ (remove); **Clear** empties the list after a confirmation click.
- **In sync.** A player who joins mid-track starts from the right second. The
  next track is sent in advance, so there is almost no gap between tracks.
- **Personal volume.** Volume 0 turns the radio off just for you — the server
  then stops sending you files. Normal game music is never muted.
- **Survives restarts.** The playlist, position and modes are saved in the world
  folder. While nobody is listening, the radio pauses itself.
- **Friendly to players without the mod.** They join as usual and get a chat
  message with a download link. A player with an outdated version is asked to
  update; the radio stays silent for them instead of kicking them.

Only links to single tracks for now — no albums or playlists. Tracks longer than
20 minutes are rejected: files travel through the game connection.

## How it works

```
player: radio screen (key ",") or /syrradio add <link>
   ↓
mod on the server: playlist ──HTTP──► bridge: source → download → ffmpeg → ogg
   ↓                          ◄──ogg──
the file is sent to players in 32 KB chunks over the game connection
   ↓
mod on the client: plays the ogg with the game sound engine, same position for everyone
```

- `mod/` — the Forge mod.
- `bridge/` — a small Python service that knows everything about music sources.
  It runs next to the Minecraft server and is not exposed to the internet.
  A new source is a module in `bridge/app/sources/`; the mod doesn't change.

## Installation

**Players:** put `syrradio-forge-1.20.1-<version>.jar` from
[Releases](https://github.com/Syrnnik/SyrRadio/releases/latest) into `mods/`.
Nothing else is needed — no accounts or subscriptions, the server downloads
everything.

**Server:** the same jar in `mods/`, plus the bridge and a config file — see
[docs/deploy/server.md](docs/deploy/server.md) and
[docs/deploy/bridge.md](docs/deploy/bridge.md).

## Using the radio

Press **`,`** (rebind in *Controls → SyrRadio*) to open the radio screen:

| Part | What it does |
|------|--------------|
| Progress bar | Hover to see the knob, drag it to seek |
| 🔀 ⏮ ⏯ ⏭ 🔁 | Shuffle, previous (restarts the track after 3 s), pause, next, repeat |
| Volume | Your own volume; 0 = radio off for you |
| Link field | Paste a track link, press Enter or **Add** |
| Track list | Drag rows to reorder, ▶ to play, ✕ to remove, **Clear** to empty |

### Commands

Available to every player, the console and RCON:

| Command | Description |
|---------|-------------|
| `/syrradio add <link>` | Add a track to the end of the list |
| `/syrradio next` / `skip` / `prev` | Next / previous track |
| `/syrradio pause` | Pause or resume |
| `/syrradio repeat off\|all\|one` | Repeat mode |
| `/syrradio shuffle on\|off` | Shuffle |
| `/syrradio play <n>` / `remove <n>` | Play / remove track number `n` |
| `/syrradio clear` | Clear the whole list |
| `/syrradio queue` | Show the list: `·` played, `▶` playing, `⏸` paused, `…` waiting |

## Configuration

`config/syrradio-server.toml` (server only):

| Setting | Default | Description |
|---------|---------|-------------|
| `bridgeUrl` | `http://syrradio-bridge:8080` | Where the bridge lives |
| `bridgeToken` | — | `BRIDGE_TOKEN` from the bridge's `.env` |
| `chunksPerTick` | `4` | 32 KB chunks per player per tick (~2.5 MB/s) |
| `joinMessages.missingMod` | Russian text | Chat message for players without the mod |
| `joinMessages.outdatedMod` | Russian text | Chat message for players with an old version |
| `joinMessages.downloadUrl` | GitHub Releases | Link appended to both messages |

`config/syrradio-client.toml`: `volume` (0–1, default 0.5), also set from the screen.

## Building from source

Requires JDK 17.

```sh
cd mod
./gradlew build      # → build/libs/syrradio-forge-1.20.1-<version>.jar
```

Local test setup (dev server, client and bridge on one machine):
[docs/dev/local.md](docs/dev/local.md).

## Documentation

Everything else — architecture, bridge, deployment, release process — is in
[docs/](docs/README.md) (in Russian).
