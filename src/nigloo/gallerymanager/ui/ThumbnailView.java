package nigloo.gallerymanager.ui;

import java.io.IOError;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.image.ImageView;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.ui.util.CustomImage;
import nigloo.gallerymanager.ui.util.ImageCache;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class ThumbnailView extends ImageView
{
	private static javafx.scene.image.Image THUMBNAIL_LOADING_PLACEHOLDER = null;
	private static javafx.scene.image.Image THUMBNAIL_CANNOT_LOAD_PLACEHOLDER = null;
	
	private static synchronized void lazyLoadPlaceholders()
	{
		if (THUMBNAIL_LOADING_PLACEHOLDER == null)
		{
			try
			{
				THUMBNAIL_LOADING_PLACEHOLDER = new javafx.scene.image.Image(ThumbnailView.class.getModule()
				                                                                                .getResourceAsStream("resources/images/loading.gif"));
				
				THUMBNAIL_CANNOT_LOAD_PLACEHOLDER = new javafx.scene.image.Image(ThumbnailView.class.getModule()
				                                                                                    .getResourceAsStream("resources/images/image_deleted.png"));
			}
			catch (IOException e)
			{
				throw new IOError(e);
			}
		}
	}
	
	@Inject
	private ImageCache imageCache;
	
	private final Image galleryImage;
	private boolean displayed;
	
	private javafx.scene.image.Image fxImage;
	
	public ThumbnailView(Image galleryImage)
	{
		super();
		this.galleryImage = Objects.requireNonNull(galleryImage, "galleryImage");
		this.displayed = false;
		this.fxImage = null;
		lazyLoadPlaceholders();
		setImage(THUMBNAIL_LOADING_PLACEHOLDER);
		Injector.init(this);
		
		visibleProperty().addListener(obs -> onDisplayedChange(isVisible()));
	}
	
	public Image getGalleryImage()
	{
		return galleryImage;
	}
	
	private void onDisplayedChange(boolean displayed)
	{
		if (this.displayed == displayed)
			return;
		
		this.displayed = displayed;
		if (displayed)
		{
			fxImage = imageCache.getThumbnail(galleryImage, true);
			double progress = fxImage instanceof CustomImage customImage ? customImage.loadingProgressProperty().get() : fxImage.getProgress();
			if (progress == 1)
				setImage(fxImage);
			else
			{
				new ProgressListener(this);
			}
		}
		else if (fxImage != null)
		{
			imageCache.cancelLoadingThumbnail(galleryImage);
			fxImage = null;
		}
	}
	
	private static class ProgressListener implements ChangeListener<Object>
	{
		private final ThumbnailView imageView;
		private final javafx.scene.image.Image fxImage;
		
		public ProgressListener(ThumbnailView imageView)
		{
			this.imageView = imageView;
			this.fxImage = imageView.fxImage;
			if (fxImage instanceof CustomImage customImage)
			{
				customImage.loadingProgressProperty().addListener(this);
				customImage.loadingExceptionProperty().addListener(this);
			}
			else
			{
				this.fxImage.progressProperty().addListener(this);
				this.fxImage.exceptionProperty().addListener(this);
			}
		}
		
		@Override
		public void changed(ObservableValue<? extends Object> observable, Object oldValue, Object newValue)
		{
			if (fxImage instanceof CustomImage customImage)
			{
				if (observable == customImage.loadingProgressProperty())
				{
					if (((Number) newValue).doubleValue() == 1)
					{
						imageView.setImage(fxImage);
						customImage.loadingProgressProperty().removeListener(this);
						customImage.loadingExceptionProperty().removeListener(this);
					}
				}
				else if (observable == customImage.loadingExceptionProperty())
				{
					customImage.loadingProgressProperty().removeListener(this);
					customImage.loadingExceptionProperty().removeListener(this);
					
					if (!(newValue instanceof CancellationException))
						imageView.setImage(THUMBNAIL_CANNOT_LOAD_PLACEHOLDER);
				}
			}
			else
			{
				if (observable == fxImage.progressProperty())
				{
					if (((Number) newValue).doubleValue() == 1)
					{
						imageView.setImage(fxImage);
						fxImage.progressProperty().removeListener(this);
						fxImage.exceptionProperty().removeListener(this);
					}
				}
				else if (observable == fxImage.exceptionProperty())
				{
					fxImage.progressProperty().removeListener(this);
					fxImage.exceptionProperty().removeListener(this);
					
					if (!(newValue instanceof CancellationException))
						imageView.setImage(THUMBNAIL_CANNOT_LOAD_PLACEHOLDER);
				}
			}
		}
	}
	
	@Override
	public int hashCode()
	{
		return galleryImage.hashCode();
	}
	
	@Override
	public boolean equals(Object obj)
	{
		return obj instanceof ThumbnailView other ? galleryImage.equals(other.galleryImage) : false;
	}
	
	@Override
	public String toString()
	{
		return getClass().getSimpleName() + "{galleryImage=" + galleryImage + "}";
	}
}
