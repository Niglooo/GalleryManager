package nigloo.gallerymanager.autodownloader;

public class KemonoFanboxDownloader extends KemonoDownloader {
    public KemonoFanboxDownloader() {
        super("fanbox");
    }

    @Override
    public DownloaderType getType()
    {
        return DownloaderType.KEMONO_FANBOX;
    }
}
