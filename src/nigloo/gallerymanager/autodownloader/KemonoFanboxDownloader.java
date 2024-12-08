package nigloo.gallerymanager.autodownloader;

public class KemonoFanboxDownloader extends KemonoDownloader {
    public KemonoFanboxDownloader() {
        super("fanbox", false);
    }

    @Override
    public DownloaderType getType()
    {
        return DownloaderType.KEMONO_FANBOX;
    }
}
