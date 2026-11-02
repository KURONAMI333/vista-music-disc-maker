# Vista & Music Disc Maker

NeoForge 1.21.1 addon that plays a Music Disc Maker Golden Jukebox video on Vista
screens while Music Disc Maker keeps ownership of the audio.

## Building

Third-party jars are not redistributed here. Put these in `libs/` before building:

- Music Disc Maker 3.1.2 (NeoForge 1.21.1) - CurseForge / Modrinth
- Vista 5.5.7 (NeoForge 1.21.1) - https://www.curseforge.com/minecraft/mc-mods/cameramod
- Moonlight Lib 3.4.0 (NeoForge 1.21.1) - https://www.curseforge.com/minecraft/mc-mods/selene

Then run `./gradlew build` with JDK 21. The jar lands in `build/libs/`.
