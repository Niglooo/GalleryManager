package nigloo.gallerymanager.autodownloader;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum DownloaderType
{
    FANBOX(FanboxDownloader.class),
    PIXIV(PixivDownloader.class),
    TWITTER(TwitterDownloader.class),
    MASONRY(MasonryDownloader.class),
    TOOMICS(ToomicsDownloader.class),
    PATREON(PatreonDownloader.class),
    ARCHIVE_PATREON(ArchivePatreonDownloader.class),
    ARCHIVE_FANBOX(ArchiveFanboxDownloader.class),
    SUBSCRIBESTAR(SubscribeStarDownloader.class),
    BLUESKY(BlueskyDownloader.class),
    ;


    private final Class<? extends Downloader> implClass;
}
