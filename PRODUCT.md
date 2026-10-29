# Geode

> This is an overview of the app, for understanding the app. It is not meant to list every aspect of Geode. It gives Claude context on what we are building.

## What we are building

Geode is an Android music player that turns music into visuals. Every song becomes a moving picture that reacts to the sound. You choose the look, make it your own, and keep the moments you like as a recording or a rendered video.

People come to Geode for the visuals, but it is also a complete music player that works just as well when you are not watching. It is made for anyone who loves music and visuals: easy to start with, with depth for people who want to go further. Geode works fully offline, with no account and no sign-in. The internet is only needed to share a video.

## Who it is for

Geode is an Android app, built phone-first, for one person using it on their own device. It is not built for iOS, desktop or the web, and it has no shared or multi-user features.

## What Geode is not

- Not a streaming service. It plays the music already on your phone.
- Not a social app. There is no feed, no profiles and no following.
- Not an online service. There is no account and no cloud sync.
- Not a place to share looks. Presets and Scene Presets stay on your phone. Only finished videos are shared.

## What matters most

When choices have to be made, in this order:

1. Smooth visuals that stay in sync with the audio.
2. Flash safety for the people watching.
3. Battery and heat, so the app can run for a long time.

## The shape of the app

Every visit starts on the home screen. It shows the last song and the last visual, ready to resume, and leads to the four main places in the app:

- **Music Library**: where your music lives and plays.
- **Visuals**: where the music becomes a visual.
- **Studio**: where music and visuals become video.
- **Settings**: your preferences, tutorials and help.

The usual path through the app: you play a song in the Music Library, tap the eye icon to see it as a visual, change the look until you like it, and record it or send it to Studio to become a video you can save or share.

## Music Library

A full music player. It finds the music on your phone and lets you browse and search tracks, artists, albums and playlists. It has playlists, a queue, transitions between songs, a sleep timer and sound settings. Music keeps playing in the background, with a mini player in the app and controls on the lock screen and in the notification. A home-screen widget shows what is playing.

Songs played here drive the visual, and any song or playlist can be sent to Studio.

## Visuals

A visual reacts to a song from your library, or to the sound in the room through the microphone.

Every look belongs to a **Style Family**, a group of visuals that move and react to music in the same way. Each Family has **Styles**, variations with their own look.

Geode also includes **MilkDrop**, the classic music visualizer, with a set of built-in MilkDrop presets. You can import your own MilkDrop packs and textures.

You can watch a visual full screen, see your changes on it straight away, and cast it to a bigger screen such as a TV. The visual can also be set as the phone's live wallpaper.

## Making it yours

Customization changes how a visual looks and behaves, in three layers:

1. **Macros**: a small set of big controls that each change several settings at once.
2. **Customization**: clear settings anyone can understand.
3. **Advanced**: a full modulation system for people who want to go deep.

A setting means the same thing in every Family, while each Family keeps its own way of responding to the music. A look can be saved as a **Scene Preset** (the Family, the Style and every setting) or a **Preset** (just the settings). Geode remembers your current look even if you don't save it.

Moving the visual by tilting the phone, so that gravity pulls it around, is a planned feature that is not built yet.

## Recording and video

While you watch, you can record the visual and the sound together, and keep changing the visual while it records.

Studio turns music and visuals into finished video. In the **export lounge** Geode renders a song or a whole playlist into a video, in the format and quality you choose, in the background while you keep listening. The render works from the music file, so it can look ahead in the song, which the live visual cannot. The **editing lounge** is a video editor for trimming and finishing clips before you save them to your phone or share them.

## Safety

Visuals can flash, so Geode warns you and asks for consent before the first one plays, and offers ways to limit or turn off flashing. Reduced motion and readable text keep the app comfortable for everyone. A short tour on first open introduces the main features.

## Where to find more

Terms and design decisions are recorded in `GLOSSARY.md` and `docs/adr/` as they are settled. These may not exist yet.
