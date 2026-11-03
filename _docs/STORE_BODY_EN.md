Put a disc made from a YouTube link into a Music Disc Maker Golden Jukebox, and every Vista television linked to that jukebox plays the video in time with the music.

The sound still comes from the jukebox, and the jukebox stays in charge: pausing, resuming, seeking and ejecting the disc apply to the picture too. A paused disc leaves the last frame on screen.

## Required mods

- **Vista** ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/vista) · [Modrinth](https://modrinth.com/mod/vista_tv)) adds cameras, televisions, cassettes and live feeds. Its Television and Hollow Cassette are what this addon shows the video on.
- **Music Disc Maker** ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/music-disc-maker) · [Modrinth](https://modrinth.com/mod/music-disc-maker)) turns YouTube, SoundCloud and other audio links into playable music discs, and adds the Golden Jukebox.

## How to use

1. Use a Hollow Cassette on the Golden Jukebox.
2. Put that cassette into a Television.
3. Insert a disc made from a YouTube link into the jukebox.

Several televisions can show the same jukebox. Install the addon on the server and on every player's client.

## Downloads and limits

Each player's game fetches the video from YouTube itself; the server only tells clients what is playing. On first use the addon downloads yt-dlp and Deno from their official GitHub releases and the FFmpeg libraries from Maven Central, checks each file against a fixed checksum, and keeps them in the `mdm-vista` folder of the game directory.

Video works on Windows (x64) and macOS (Apple Silicon). On other systems the music still plays but the televisions show no video. Video is played at 480p when the video offers it. Only discs made from YouTube links have a picture.

<p><a href="https://www.patreon.com/KURONAMI333"><img src="https://media.forgecdn.net/attachments/description/1564253/description_0b5d69f3-bf0d-4a38-8894-6883abaa65e9.png" width="250" height="100" alt="Support my mods on Patreon"></a> <a href="https://x.com/kuronami333"><img src="https://media.forgecdn.net/attachments/description/1564253/description_0ed421f9-f6e1-4334-8e1e-fac25c804fe6.png" width="250" height="100" alt="Follow @kuronami333 on X"></a> <a href="https://www.youtube.com/channel/UCoimycJCVZmxNKyX9Vr7Apg"><img src="https://media.forgecdn.net/attachments/description/1564253/description_b953be14-e0c1-4290-b5d3-5ae75107ce90.png" width="250" height="100" alt="Watch KURONAMI on YouTube"></a></p>

<p><img src="https://media.forgecdn.net/attachments/description/1537959/description_582d8b5a-1840-40ee-b403-c361b7f74006.png" alt="Need a custom mod?" width="776" height="95"></p>

<p align="center" style="text-align: center;">Paid commissions for videos, streams and servers. Send me your idea: <a href="mailto:kuronami.mods@gmail.com">kuronami.mods@gmail.com</a></p>

<p><img src="https://media.forgecdn.net/attachments/description/1537959/description_a7712950-22d2-40cb-94f6-b8edc51870b9.png" alt="" width="776" height="56"></p>

All Rights Reserved. Modpacks may include this addon without permission or credit.

Video downloading uses [yt-dlp](https://github.com/yt-dlp/yt-dlp), [Deno](https://github.com/denoland/deno) and [FFmpeg](https://ffmpeg.org/) through [JavaCV](https://github.com/bytedeco/javacv).

Bugs and questions: comment on the CurseForge page, or DM [@kuronami333 on X](https://x.com/kuronami333).

[Source](https://github.com/KURONAMI333/vista-music-disc-maker)
