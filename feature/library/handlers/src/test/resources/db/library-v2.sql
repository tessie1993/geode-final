-- A Library database as version 2 wrote it, with data in every table. The migration test opens it
-- at the current version and checks that nothing was lost (ADR-0004). Never edit this file: a new
-- version adds its own sample beside it.
CREATE TABLE IF NOT EXISTS `songs` (`trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `albumArtist` TEXT NOT NULL, `genre` TEXT NOT NULL, `composer` TEXT NOT NULL, `path` TEXT NOT NULL, `format` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `addedAtMs` INTEGER NOT NULL, `tagsRead` INTEGER NOT NULL, PRIMARY KEY(`trackId`));
CREATE TABLE IF NOT EXISTS `edits` (`trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `artwork` TEXT, PRIMARY KEY(`trackId`));
CREATE TABLE IF NOT EXISTS `favourites` (`trackId` TEXT NOT NULL, PRIMARY KEY(`trackId`));
CREATE TABLE IF NOT EXISTS `excluded_folders` (`path` TEXT NOT NULL, PRIMARY KEY(`path`));
CREATE TABLE IF NOT EXISTS `smart_playlists` (`name` TEXT NOT NULL, `matchAll` INTEGER NOT NULL, `songLimit` INTEGER NOT NULL, PRIMARY KEY(`name`));
CREATE TABLE IF NOT EXISTS `smart_rules` (`playlist` TEXT NOT NULL, `position` INTEGER NOT NULL, `field` TEXT NOT NULL, `op` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`playlist`, `position`));
INSERT INTO `songs` VALUES ('content://media/external/audio/media/12', 'Night Drive', 'Ann', 'Roads', 'Various', 'Synthwave', 'Cole', '/storage/emulated/0/Music/night.flac', 'flac', 215000, 1700000000000, 1);
INSERT INTO `songs` VALUES ('content://media/external/audio/media/7', 'Low Tide', 'Bob', 'Coast', '', '', '', '/storage/emulated/0/Music/tide.mp3', 'mp3', 187000, 1700000100000, 0);
INSERT INTO `edits` VALUES ('content://media/external/audio/media/7', 'Low Tide (edit)', 'Bob B', 'Coastline', 'content://media/external/images/media/3');
INSERT INTO `edits` VALUES ('content://media/external/audio/media/12', 'Night Drive', 'Ann', 'Roads', NULL);
INSERT INTO `favourites` VALUES ('content://media/external/audio/media/12');
INSERT INTO `excluded_folders` VALUES ('/storage/emulated/0/Ringtones');
INSERT INTO `smart_playlists` VALUES ('Night', 1, 25);
INSERT INTO `smart_rules` VALUES ('Night', 0, 'TITLE', 'CONTAINS', 'night');
