package nigloo.gallerymanager.model;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import nigloo.gallerymanager.ui.FXImageVideoWrapper;
import nigloo.gallerymanager.ui.util.ImageCache;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class Image
{
	long id;
	private Path path;
	private Set<TagReference> tags = new HashSet<>();
	
	private transient Set<String> implicitTags = null;
	
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
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("{id=");
		sb.append(id);
		sb.append(", path=");
		sb.append(path);
		
		ImageCache imageCache = Injector.getInstance(ImageCache.class);
		
		javafx.scene.image.Image thumbnail = imageCache.getCachedThumbnail(this);
		if (thumbnail != null)
		{
			sb.append(", thumbnail(");
			sb.append((int) (thumbnail.getProgress() * 100));
			sb.append("%)");
		}
		FXImageVideoWrapper fxImageVideo = imageCache.getAsyncFXImageVideo(this);
		if (fxImageVideo != null)
		{
			sb.append(", fxImageVideo(");
			sb.append((int) (fxImageVideo.getProgressProperty().get() * 100));
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
		        || extention.equals("png") || extention.equals("gif") || isActuallyVideo(file);
	}
	
	public static boolean isActuallyVideo(Path file)
	{
		String filename = file.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		if (posExt == -1)
			return false;
		
		String extention = filename.substring(posExt + 1).toLowerCase(Locale.ROOT);
		return extention.equals("mp4");
	}
	
	public boolean isActuallyVideo()
	{
		return isActuallyVideo(path);
	}
}
