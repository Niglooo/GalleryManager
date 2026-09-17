package nigloo.gallerymanager.autodownloader;

public class ArchiveFanboxDownloader extends ArchiveDownloader
{
    public ArchiveFanboxDownloader() {
        super("fanbox");
    }

    @Override
    public DownloaderType getType()
    {
        return DownloaderType.ARCHIVE_FANBOX;
    }
}
