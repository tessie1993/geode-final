# Geode

> This is an overview of the app, to help with understanding Geode as a whole. It is context only: it is not used to make decisions.

Music you can see.

## What Geode is

Geode is an Android phone app that plays your music and turns it into visuals. Every song comes alive as a moving picture that reacts to the sound, in a look you can choose and make your own. You can then keep it: record it live, or render a song or a whole playlist into a video to save or share.

People download Geode for the music visuals. It also works as a plain music player when you don't want to watch.

It is for anyone who loves music and visuals, not a specialist tool. It is easy to start with, with more depth for people who want to dig in. Geode works fully offline: there is no account and no sign-in. The internet is only needed to share videos.

## The four sections

From the home screen you reach the four main sections:

- **Music Library**: your music.
- **Visuals**: watch, choose and customize the visual.
- **Studio**: turn music and visuals into video, and edit videos.
- **Settings**: your preferences and the tutorials.

## Home screen

The home screen is a hub. It shows artwork of the last visual you played and the last song, ready to resume, and it shows what you can do in the app. You can change what the home screen shows in Settings, for example shortcuts, Presets or your last played.

## Music Library

A normal music library: tracks, artists, albums and playlists, with browsing and search. Geode finds the music on your phone.

While a song plays, a mini player sits at the bottom or the top of the screen, whichever you choose. An **eye icon** at the top opens the visual that is currently loaded. If no visual is loaded yet, it takes you to the Visuals section to choose one.

The library is the way into the visuals: songs played here drive the visual. It is also where you pick songs and playlists to turn into videos.

### Player features

- **Playlists and queue**: your own playlists, smart playlists that fill themselves by rules, importing and exporting playlists, a play queue, favourites and listening history.
- **Listening tools**: a sleep timer that fades the music out, A-B loop to repeat part of a song, resume where you left off, and smooth transitions between songs (gapless or crossfade).
- **Sound tools**: an equalizer, crossfeed for headphones, and volume levelling so songs play at a similar loudness.
- **Search** across your music.

### Outside the app

- **Lock screen and notification**: media controls, and the music keeps playing in the background.
- **Home-screen widget**: shows what's playing, with controls.
- **Live wallpaper**: the visual as your phone's wallpaper.

## Visuals

This is where the music comes to life. You come here to watch, to set a mood, and to make a look your own. It is also used for:

- **Live performance**: visualizing a musician or DJ through the microphone.
- **Content creation**: making a look to record or render into clips for social media.

### Sound sources

The visual reacts to sound. The sound comes from:

- **Your music library**: the song that is playing.
- **The microphone**: sound in the room, such as music from a speaker, a live performance or a party. The visual reacts to the mic the same way it reacts to library music.

More sound sources may come later.

### Style Families, Styles and Presets

- **Style Family**: a group of Styles built on the same base method. Each Family moves and reacts to music in its own way. New Families are added regularly.
- **Style**: one variation within a Family. It uses the same base but has its own look and extra touches.
- **Preset**: a saved set of customization settings only. It can be used on other Styles.
- **Scene Preset**: a saved Family and Style together with every customization, a complete look in one tap.

Presets and Scene Presets can be organised into folders.

### MilkDrop

Geode includes MilkDrop visuals, a classic kind of music visual with a large world of community-made presets. Geode ships a set of MilkDrop presets, and you can import your own MilkDrop packs and their textures.

### Choosing a look

When no visual is showing, the Visuals section opens on the chooser. Families are shown first, with previews. You pick a Family, then a Style, then optionally a Preset. Or you jump straight to a saved Scene Preset.

### Watching

Once a Style is chosen, the visual plays. You can:

- Switch to **full-screen** mode, showing only the visual.
- **Drag down** to look at the whole visual behind the panels.
- Adjust a **translucency slider**, so the panels are see-through and your choices and edits show on the visual straight away.
- **Long-press** in full screen to bring up a side bar with the Macros and a red **record** button. Which gestures do what is set in Settings.

When you switch to another look, the visual changes over with a smooth transition.

When you leave the Visuals section, the visual pauses and the music keeps playing. When the next song starts, the same look continues.

### Customization

Customization changes how the visual looks and behaves: speed, motion, colour, how it reacts to the music, and how it reacts to you when you touch the visual. Tilting the phone so that gravity pulls the visual is a planned feature that is not built yet. It has three layers:

1. **Macros**: six big knobs, each bundling settings that belong together, for a quick and easy feel.
2. **Customization**: clear, intuitive settings that anyone can use.
3. **Advanced**: a detailed modulation matrix for people who want to go deep. It has a **patch board** for wiring parts of the sound to settings, and **modulators** (such as LFOs) that move a setting by themselves over time.

A **randomize** button shuffles the settings for a surprise look, and you can save and reuse colour **palettes**.

A customization has the same effect in every Style Family. Each Family still keeps its own character in how it responds to the music.

You can keep your work by saving a **Scene Preset** or a **Preset**. Your current look is also kept automatically, even if you don't save it.

### Recording

The record button captures the visual and the sound live, as you watch:

- It never records the app's own screens.
- It keeps recording even if you move to another screen.
- You can keep playing with Macros and touch while recording, and those changes appear in the video.
- The recording is saved to your phone and can be opened in Studio.

By default a recording is one video with the visual and music together. In Settings you can choose to also save every control change (Macros, customizations) next to the video as separate lanes. You can edit those lanes later in Studio.

### Casting

You can send the visual to a bigger screen, such as a TV.

## Studio

Studio is where music and visuals become video. It has two parts.

### Export lounge

Here you render a video offline from the music:

- **What**: a single song, or a whole playlist. By default, what is playing.
- **Look**: by default, the current visual. You can also pick a Scene Preset.
- **Format and quality**: vertical, square or wide, and the resolution.
- **Loop**: optionally render a short video that loops seamlessly.
- **Chapters**: a long playlist video gets chapter markers, one per song.
- **Loudness**: the audio is levelled to a good loudness for sharing.

While rendering you see progress and the time left. Rendering keeps going in the background, you can keep listening to music meanwhile, and a notification tells you when the video is ready.

A rendered video reacts to the music even better than the live visual, because the render can look ahead in the song.

There are three ways to start an export:

- **Quick Export**: renders the song that is playing, from the start, with the current visual settings.
- **From Studio**, in the export lounge.
- **From the Music Library**, from a song or playlist.

### Editing lounge

Here you edit videos: ones Geode rendered, recordings, or videos you already had. You can:

- Trim and cut, on a **timeline** with keyframes, curves, markers and undo.
- **Auto-cut** clips on the beat of the music.
- Join clips, with **transitions** between them.
- Change speed with **speed ramps**.
- Add text, such as the song title, artist, captions, subtitles or lyrics, timed to the music.
- Apply filters and **colour grading**.

Your work is kept as a **project** you can save and come back to.

If a recording was saved with its control lanes, you can also see those lanes, edit them, add new changes, and re-render a clean video from the song.

When you're done, you save the video to your phone or share it through Android's share menu.

## Settings

All your preferences, in tabs sorted by topic:

- **Home screen**: what the hub shows.
- **Visualizer**: gestures, panel translucency, full-screen behaviour, and recording options such as saving control lanes.
- **Library and player**: mini-player position, default folders, and playback.
- **Export**: default format, quality and save location.
- **Accessibility and safety**: the flash-safety options and reduced motion (see below).
- **Language**: the app's language.
- **Tutorials**: the tour and help, available any time.
- **Help and diagnostics**: send a problem report, with personal data removed.
- **Import**: bring over your library and data from an older version of Geode.

## Safety and accessibility

- **Flash safety**: visuals can flash. Geode shows a warning and asks for consent before the first visual. A flash limiter and a no-flash mode protect people who are sensitive to flashing light.
- **Reduced motion**: calmer animations in the app for people who need it.
- **Readable contrast**: text stays readable for everyone.

## A typical session

1. **First open.** Geode asks permission to read your music and scans your library. A short tour shows the main features: the Library, Visuals, what Macros do, and the Studio. You can ask for more information, and you can turn features on or off. You land on the home hub.
2. **Play a song.** In the Music Library you tap a song. It plays, and the mini player appears.
3. **See it.** You tap the eye icon. With no look chosen yet, the Visuals chooser opens.
4. **Choose a look.** You pick a Family, a Style and maybe a Preset. The visual starts moving to the song.
5. **Make it yours.** You open Customization to make it bluer and wilder, with the panels see-through so you can watch the change happen.
6. **Keep it.** You save it as a Scene Preset.
7. **Play with it.** In full screen you long-press, turn the Macros, touch the visual, and hit record.
8. **Make a video.** In Studio you render the whole song as a vertical video, keep listening while it renders, then trim it, add the song title and share it.
9. **Next day.** The home screen shows yesterday's song and look, ready to resume.
10. **At a party.** You switch the visual to the microphone, go full screen, and cast it to the TV.
