-- A Studio database as version 1 wrote it, with data in every table. The migration test opens it
-- at the current version and checks that nothing was lost (ADR-0004). Never edit this file: a new
-- version adds its own sample beside it.
CREATE TABLE IF NOT EXISTS `projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `videos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `file` TEXT NOT NULL, `thumbnail` TEXT, `lengthMs` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `song` TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS `index_videos_projectId` ON `videos` (`projectId`);
CREATE TABLE IF NOT EXISTS `choices` (`id` INTEGER NOT NULL, `defaultProjectId` INTEGER, PRIMARY KEY(`id`));
INSERT INTO `projects` (`id`, `name`, `createdAtMs`) VALUES (1, 'Trip', 1700000000000);
INSERT INTO `projects` (`id`, `name`, `createdAtMs`) VALUES (2, 'Gigs', 1700000100000);
INSERT INTO `videos` VALUES (1, 1, 'RENDER', 'Beach.mp4', 'content://media/external/video/media/31', NULL, 214000, 1920, 1080, 'Night Drive');
INSERT INTO `videos` VALUES (2, 2, 'RECORDING', 'Stage.mp4', 'content://tree/videos/stage.mp4', 'content://thumb/2', 65000, 1080, 1920, '');
INSERT INTO `choices` VALUES (0, 2);
