package nigloo.gallerymanager.autodownloader;

public class ArchivePatreonDownloader extends ArchiveDownloader
{
    public ArchivePatreonDownloader() {
        super("patreon");
    }

    @Override
    public DownloaderType getType()
    {
        return DownloaderType.ARCHIVE_PATREON;
    }
}
