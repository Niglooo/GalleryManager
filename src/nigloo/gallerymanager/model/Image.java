package nigloo.gallerymanager.model;

import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class Image
{
	long id;
	private Path path;
	private Set<TagReference> tags = new HashSet<>();
	
	private transient Set<String> implicitTags = null;
	private transient SoftReference<javafx.scene.image.Image> thumbnailCache = null;
	private transient SoftReference<javafx.scene.image.Image> fxImageCache = null;
	
	@Inject
	private transient Gallery gallery;
	
	private Image()
	{
		Injector.init(this);
	}
	
	Image(Path path)
	{
		this();
		this.id = -1;
		this.path = path;
	}
	
	public void move(Path target)
	{
		if (target.isAbsolute())
			throw new IllegalArgumentException("target must be relative. Got: " + target);
		
		path = target;
		
		if (isNotSaved())
			gallery.unsavedImagesValid = false;
	}
	
	public boolean isSaved()
	{
		return this.id > 0;
	}
	
	public boolean isNotSaved()
	{
		return this.id <= 0;
	}
	
	public long getId()
	{
		return id;
	}
	
	public Path getPath()
	{
		return path;
	}
	
	public Path getAbsolutePath()
	{
		return gallery.toAbsolutePath(path);
	}
	
	public Collection<Tag> getTags()
	{
		return tags.stream().map(TagReference::getTag).collect(Collectors.toUnmodifiableSet());
	}
	
	public boolean addTag(Tag tag)
	{
		boolean added = tags.add(new TagReference(tag));
		if (added)
			implicitTags = null;
		
		return added;
	}
	
	public boolean addTag(String tagName)
	{
		boolean added = tags.add(new TagReference(gallery.getTag(tagName)));
		if (added)
			implicitTags = null;
		
		return added;
	}
	
	public boolean removeTag(Tag tag)
	{
		boolean removed = tags.remove(new TagReference(tag));
		if (removed)
			implicitTags = null;
		
		return removed;
	}
	
	public boolean removeTag(String tagName)
	{
		boolean removed = tags.remove(new TagReference(tagName));
		if (removed)
			implicitTags = null;
		
		return removed;
	}
	
	public Set<String> getImplicitTags()
	{
		if (implicitTags == null)
		{
			implicitTags = new HashSet<>();
			ArrayDeque<Tag> patentsToVisit = new ArrayDeque<>(tags.stream().map(TagReference::getTag).toList());
			
			Tag tag;
			while ((tag = patentsToVisit.poll()) != null)
				if (implicitTags.add(tag.getName()))
					patentsToVisit.addAll(tag.getParents());
			
			implicitTags = Collections.unmodifiableSet(implicitTags);
		}
		
		return implicitTags;
	}

	public javafx.scene.image.Image getThumbnail(boolean async)
	{
		javafx.scene.image.Image thumbnail = (thumbnailCache == null) ? null : thumbnailCache.get();
		if (thumbnail == null || thumbnail.isError())
		{
			try
			{
				String imageUrl = gallery.toAbsolutePath(path).toUri().toURL().toString();
				thumbnail = new javafx.scene.image.Image(imageUrl, 300, 300, true, true, async);
				thumbnailCache = new SoftReference<javafx.scene.image.Image>(thumbnail);
			}
			catch (MalformedURLException e)
			{
				throw new RuntimeException(e);
			}
		}
		
		return thumbnail;
	}
	
	public void cancelLoadingThumbnail()
	{
		javafx.scene.image.Image thumbnail = (thumbnailCache == null) ? null : thumbnailCache.get();
		if (thumbnail != null)
			thumbnail.cancel();
	}
	
	public javafx.scene.image.Image getFXImage(boolean async)
	{
		javafx.scene.image.Image fxImage = (fxImageCache == null) ? null : fxImageCache.get();
		if (fxImage == null || fxImage.isError())
		{
			try
			{
				String imageUrl = gallery.toAbsolutePath(path).toUri().toURL().toString();
				fxImage = new javafx.scene.image.Image(imageUrl, async);
				fxImageCache = new SoftReference<javafx.scene.image.Image>(fxImage);
			}
			catch (MalformedURLException e)
			{
				throw new RuntimeException(e);
			}
		}
		
		return fxImage;
	}
	
	public void cancelLoadingFXImage()
	{
		javafx.scene.image.Image fxImage = (fxImageCache == null) ? null : fxImageCache.get();
		if (fxImage != null)
			fxImage.cancel();
	}
	
	@Override
	public String toString()
	{
		javafx.scene.image.Image img;
		
		StringBuilder sb = new StringBuilder();
		sb.append("{id=");
		sb.append(id);
		sb.append(", path=");
		sb.append(path);
		if (thumbnailCache != null && (img = thumbnailCache.get()) != null)
		{
			sb.append(", thumbnail(");
			sb.append((int) (img.getProgress() * 100));
			sb.append("%)");
		}
		if (fxImageCache != null && (img = fxImageCache.get()) != null)
		{
			sb.append(", fxImage(");
			sb.append((int) (img.getProgress() * 100));
			sb.append("%)");
		}
		sb.append("}");
		
		return sb.toString();
	}
	
	public static boolean isImage(Path file)
	{
		String filename = file.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		if (posExt == -1)
			return false;
		
		String extention = filename.substring(posExt + 1).toLowerCase(Locale.ROOT);
		return extention.equals("jpg") || extention.equals("jpeg") || extention.equals("jfif")
		        || extention.equals("png") || extention.equals("gif");
	}
}
