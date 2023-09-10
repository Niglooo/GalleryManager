package nigloo.gallerymanager.autodownloader;

public class KemonoPatreonDownloader extends KemonoDownloader {
    public KemonoPatreonDownloader() {
        super("patreon", true);
    }

    @Override
    public DownloaderType getType()
    {
        return DownloaderType.KEMONO_PATREON;
    }
}
