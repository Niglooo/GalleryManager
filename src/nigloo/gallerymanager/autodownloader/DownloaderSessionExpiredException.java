package nigloo.gallerymanager.autodownloader;

public class DownloaderSessionExpiredException extends Exception
{
    public DownloaderSessionExpiredException() {
        super("Session expired");
    }
}
