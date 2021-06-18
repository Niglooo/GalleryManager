package nigloo.gallerymanager.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import nigloo.gallerymanager.autodownloader.FanboxDownloader;
import nigloo.tool.collection.WeakIdentityHashSet;


public class Gallery {

	private transient Path rootFolder;
	
	private List<Artist> artists = new ArrayList<>();
	private List<Image> images = new ArrayList<>();
	private SlideShowParameters slideShowParameter = new SlideShowParameters();
	
	private transient long nextId = 1;
	transient WeakIdentityHashSet<ImageReference> allImageReferences = new WeakIdentityHashSet<>();
	
	/*
	 * Need to be called just after deserialization
	 */
	public void finishConstruct()
	{
		nextId = images.stream().mapToLong(Image::getId).max().orElse(0) + 1;
	}

	public Path getRootFolder() {
		return rootFolder;
	}

	public void setRootFolder(Path rootFolder) {
		if (this.rootFolder != null)
			throw new IllegalStateException("rootFolder already set ("+rootFolder+")");
		
		this.rootFolder = rootFolder;
	}
	
	public Path toRelativePath(Path path)
	{
		return path.isAbsolute() ? rootFolder.relativize(path) : path;
	}
	
	public Path toAbsolutePath(Path path)
	{
		return path.isAbsolute() ? path : rootFolder.resolve(path);
	}
	
	public Image findImage(long imageId)
	{
		return images.stream().filter(image -> image.getId() == imageId).findAny().orElse(null);
	}
	
	public Image findImage(Path path)
	{
		final Path relPath = toRelativePath(path);
		
		return images.stream().filter(image -> image.getPath().equals(relPath)).findAny().orElse(null);
	}
	
	public Collection<Image> findImagesIn(Path path)
	{
		final Path absPath = toAbsolutePath(path);
		
		return images.stream().filter(image -> image.getAbsolutePath().startsWith(absPath)).toList();
	}
	
	public void saveImage(Image image)
	{
		if (image.isSaved())
			return;
		
		image.id = nextId++;
		images.add(image);
	}
	
	public void deleteImages(Collection<Image> images)
	{
		for (Artist artist : artists)
			for (FanboxDownloader autoDownloader : artist.getAutodownloaders())
				autoDownloader.stopHandling(images);
			
		// This last or we break every ImageReference
		this.images.removeAll(images);
	}
	
	public List<Image> getImages()
	{
		return Collections.unmodifiableList(images);
	}
	
	public List<Artist> getArtists()
	{
		return artists;
	}
	
	public SlideShowParameters getSlideShowParameter()
	{
		return slideShowParameter;
	}
	
	public void compactIds()
	{
//		java.util.Map<Path, java.nio.file.attribute.FileTime> time = new java.util.HashMap<>(images.size());
//		for (Image image : images)
//			try
//			{
//				time.put(image.getPath(), java.nio.file.Files.getLastModifiedTime(toAbsolutePath(image.getPath())));
//			}
//			catch (java.io.IOException e)
//			{
//				e.printStackTrace();
//			}
//		images.sort(java.util.Comparator.comparing(i -> time.get(i.getPath())));
		
		// Force all references to load their image so updating image.id will update
		// the reference
		for (ImageReference ref : allImageReferences)
			ref.getImage();
		
		nextId = 1;
		for (Image image : images)
			image.id = nextId++;
	}
	
	public void removeImagesNotHandledByAutoDowloader()
	{
		Iterator<Image> it = images.iterator();
		
		imageLoop:
		while (it.hasNext())
		{
			Image image = it.next();
			
			for (Artist artist : artists)
				for (FanboxDownloader autoDownloader : artist.getAutodownloaders())
					if (autoDownloader.isHandling(image))
						continue imageLoop;
					
			it.remove();
		}
	}
}
