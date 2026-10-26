-- A Studio database as version 2 wrote it (#338), with data in every table: a finished Render that
-- made the first video, and one still waiting in the queue. The migration test opens it at the
-- current version and checks that nothing was lost (ADR-0004). Never edit this file: a new version
-- adds its own sample beside it.
CREATE TABLE IF NOT EXISTS `projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS `videos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `file` TEXT NOT NULL, `thumbnail` TEXT, `lengthMs` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `song` TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS `index_videos_projectId` ON `videos` (`projectId`);
CREATE TABLE IF NOT EXISTS `choices` (`id` INTEGER NOT NULL, `defaultProjectId` INTEGER, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `renders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batch` INTEGER NOT NULL, `spec` TEXT NOT NULL, `look` TEXT NOT NULL, `videoFile` TEXT, `queuedAtMs` INTEGER NOT NULL);
INSERT INTO `projects` (`id`, `name`, `createdAtMs`) VALUES (1, 'Trip', 1700000000000);
INSERT INTO `projects` (`id`, `name`, `createdAtMs`) VALUES (2, 'Gigs', 1700000100000);
INSERT INTO `videos` VALUES (1, 1, 'RENDER', 'Night Drive', 'content://media/external/video/media/41', NULL, 215000, 1920, 1080, 'Night Drive');
INSERT INTO `videos` VALUES (2, 2, 'RECORDING', 'Stage.mp4', 'content://tree/videos/stage.mp4', 'content://thumb/2', 65000, 1080, 1920, '');
INSERT INTO `choices` VALUES (0, 2);
INSERT INTO `renders` VALUES (1, 1, '{"version":1,"tracks":[{"id":"content://media/12","title":"Night Drive","lengthMs":215000}],"look":{"kind":"current"},"aspect":"R16_9","size":"FHD1080","fps":60,"quality":"HIGH","codec":"H264","length":{"kind":"whole"},"project":1,"loudness":"LEAVE_AS_IS"}', '{}', 'content://media/external/video/media/41', 1700000200000);
INSERT INTO `renders` VALUES (2, 2, '{"version":1,"tracks":[{"id":"content://media/7","title":"Low Tide","lengthMs":187000}],"look":{"kind":"current"},"aspect":"R9_16","size":"FHD1080","fps":30,"quality":"HIGH","codec":"H264","length":{"kind":"whole"},"project":2,"loudness":"MINUS_14_LUFS"}', '{}', NULL, 1700000300000);
