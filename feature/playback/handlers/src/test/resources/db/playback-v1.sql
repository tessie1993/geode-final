-- A Playback database as version 1 wrote it, with data in every table. The migration test opens it
-- at the current version and checks that nothing was lost (ADR-0004). Never edit this file: a new
-- version adds its own sample beside it.
CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `position`));
CREATE TABLE IF NOT EXISTS `plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `playedAtMs` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `session` (`id` INTEGER NOT NULL, `currentIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `session_tracks` (`position` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`position`));
INSERT INTO `playlists` (`id`, `name`, `createdAtMs`) VALUES (1, 'Evening', 1700000000000);
INSERT INTO `playlist_tracks` VALUES (1, 0, 'content://media/external/audio/media/12', 'Night Drive', 'Ann', 215000);
INSERT INTO `playlist_tracks` VALUES (1, 1, 'content://media/external/audio/media/7', 'Low Tide', 'Bob', 187000);
INSERT INTO `plays` (`id`, `trackId`, `title`, `artist`, `durationMs`, `playedAtMs`) VALUES (1, 'content://media/external/audio/media/7', 'Low Tide', 'Bob', 187000, 1700000100000);
INSERT INTO `plays` (`id`, `trackId`, `title`, `artist`, `durationMs`, `playedAtMs`) VALUES (2, 'content://media/external/audio/media/12', 'Night Drive', 'Ann', 215000, 1700000200000);
INSERT INTO `session` VALUES (0, 1, 64000);
INSERT INTO `session_tracks` VALUES (0, 'content://media/external/audio/media/12', 'Night Drive', 'Ann', 215000);
INSERT INTO `session_tracks` VALUES (1, 'content://media/external/audio/media/7', 'Low Tide', 'Bob', 187000);
