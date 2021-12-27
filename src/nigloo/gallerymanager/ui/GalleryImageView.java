package nigloo.gallerymanager.ui;

import java.util.Objects;
import java.util.function.Function;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.image.ImageView;
import nigloo.gallerymanager.model.Image;

public class GalleryImageView extends ImageView implements Displayable
{
	private final Image galleryImage;
	private final Function<Image, javafx.scene.image.Image> getFXImage;
	private boolean displayed;
	
	private javafx.scene.image.Image fxImage;
	private javafx.scene.image.Image cannotLoadImage;
	
	public GalleryImageView(Image galleryImage,
	                        Function<Image, javafx.scene.image.Image> getFXImage,
	                        javafx.scene.image.Image loadingPlaceHolder,
	                        javafx.scene.image.Image cannotLoadImage)
	{
		super();
		this.galleryImage = Objects.requireNonNull(galleryImage, "galleryImage");
		this.getFXImage = Objects.requireNonNull(getFXImage, "getFXImage");
		this.displayed = false;
		this.fxImage = null;
		setImage(loadingPlaceHolder);
		this.cannotLoadImage = cannotLoadImage;
	}
	
	public Image getGalleryImage()
	{
		return galleryImage;
	}
	
	@Override
	public void onDisplayedChange(boolean displayed)
	{
		if (this.displayed != displayed)
		{
			this.displayed = displayed;
			if (displayed)
			{
				fxImage = getFXImage.apply(galleryImage);
				if (fxImage.getProgress() == 1)
					setImage(fxImage);
				else
				{
					new ProgressListener(this);
				}
			}
			else if (fxImage != null)
			{
				fxImage.cancel();
				fxImage = null;
			}
		}
	}
	
	private static class ProgressListener implements ChangeListener<Object>
	{
		private final GalleryImageView imageView;
		private final javafx.scene.image.Image fxImage;
		
		public ProgressListener(GalleryImageView imageView)
		{
			this.imageView = imageView;
			this.fxImage = imageView.fxImage;
			this.fxImage.progressProperty().addListener(this);
			this.fxImage.errorProperty().addListener(this);
		}
		
		@Override
		public void changed(ObservableValue<? extends Object> observable, Object oldValue, Object newValue)
		{
			if (observable == fxImage.progressProperty())
			{
				if (((Number) newValue).doubleValue() == 1)
				{
					imageView.setImage(fxImage);
					fxImage.progressProperty().removeListener(this);
					fxImage.errorProperty().removeListener(this);
				}
			}
			else if (observable == fxImage.errorProperty())
			{
				if ((Boolean) newValue)
				{
					fxImage.progressProperty().removeListener(this);
					fxImage.errorProperty().removeListener(this);
					imageView.setImage(imageView.cannotLoadImage);
				}
			}
		}
	}
	
	@Override
	public int hashCode()
	{
		return galleryImage.hashCode() ^ getFXImage.hashCode();
	}
	
	@Override
	public boolean equals(Object obj)
	{
		return obj instanceof GalleryImageView other
		        ? galleryImage.equals(other.galleryImage) && getFXImage.equals(other.getFXImage)
		        : false;
	}
	
	@Override
	public String toString()
	{
		return getClass().getSimpleName() + "{galleryImage=" + galleryImage + "}";
	}
}
