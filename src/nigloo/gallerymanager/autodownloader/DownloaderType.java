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
    PATREON(PatreonDownloader.class),
    KEMONO_PATREON(KemonoPatreonDownloader.class),
    ;


    private final Class<? extends Downloader> implClass;
}
