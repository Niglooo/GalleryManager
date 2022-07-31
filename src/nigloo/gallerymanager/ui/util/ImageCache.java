package nigloo.gallerymanager.ui.util;

import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.util.Map;

import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.FXImageVideoWrapper;
import nigloo.gallerymanager.ui.VideoThumbnailImage;
import nigloo.tool.collection.WeakIdentityHashMap;
import nigloo.tool.injection.annotation.Singleton;

@Singleton
public class ImageCache
{
	private static final int THUMBNAIL_IMAGE_SIZE = 300;
	
	private final Map<Image, SoftReference<javafx.scene.image.Image>> thumbnailCache = new WeakIdentityHashMap<>();
	private final Map<Image, SoftReference<FXImageVideoWrapper>> fxImageVideoCache = new WeakIdentityHashMap<>();

	private <T> T get(Map<Image, SoftReference<T>> map, Image image) {
		SoftReference<T> ref = map.get(image);
		return ref == null ? null : ref.get();
	}
	
	private <T> T remove(Map<Image, SoftReference<T>> map, Image image) {
		SoftReference<T> ref = map.remove(image);
		return ref == null ? null : ref.get();
	}
	
	public javafx.scene.image.Image getThumbnail(Image image, boolean async)
	{
		synchronized (thumbnailCache)
		{
			javafx.scene.image.Image thumbnail = get(thumbnailCache, image);
			if (thumbnail == null || thumbnail.isError())
			{
				if (!image.isActuallyVideo())
				{
					try
					{
						String imageUrl = image.getAbsolutePath().toUri().toURL().toString();
						thumbnail = new javafx.scene.image.Image(imageUrl, THUMBNAIL_IMAGE_SIZE, THUMBNAIL_IMAGE_SIZE, true, true, async);
					}
					catch (MalformedURLException e)
					{
						throw new RuntimeException(e);
					}
				}
				else
				{
					thumbnail = new VideoThumbnailImage(THUMBNAIL_IMAGE_SIZE, THUMBNAIL_IMAGE_SIZE, image.getAbsolutePath());
				}
				
				thumbnailCache.put(image, new SoftReference<javafx.scene.image.Image>(thumbnail));
			}
			
			return thumbnail;
		}
	}
	
	public javafx.scene.image.Image getCachedThumbnail(Image image)
	{
		synchronized (thumbnailCache)
		{
			return get(thumbnailCache, image);
		}
	}
	
	public void cancelLoadingThumbnail(Image image)
	{
		synchronized (thumbnailCache)
		{
			javafx.scene.image.Image thumbnail = remove(thumbnailCache, image);
			if (thumbnail != null) 
				thumbnail.cancel();
		}
	}
	
	public FXImageVideoWrapper getAsyncFXImageVideo(Image image)
	{
		synchronized (fxImageVideoCache)
		{
			FXImageVideoWrapper fxImageVideo = get(fxImageVideoCache, image);
			if (fxImageVideo == null || fxImageVideo.exceptionProperty().get() != null)
			{
				fxImageVideo = new FXImageVideoWrapper(image.getAbsolutePath());
				fxImageVideoCache.put(image, new SoftReference<FXImageVideoWrapper>(fxImageVideo));
			}
			
			return fxImageVideo;
		}
	}
	
	public FXImageVideoWrapper getCachedFXImageVideo(Image image)
	{
		synchronized (fxImageVideoCache)
		{
			return get(fxImageVideoCache, image);
		}
	}
	
	public void cancelLoadingFXImageVideo(Image image)
	{
		synchronized (fxImageVideoCache)
		{
			FXImageVideoWrapper fxImageVideo = remove(fxImageVideoCache, image);
			if (fxImageVideo != null)
				fxImageVideo.cancel();
		}
	}
}
