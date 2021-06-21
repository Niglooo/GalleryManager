package nigloo.gallerymanager.model;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;

import nigloo.gallerymanager.autodownloader.FanboxDownloader;
import nigloo.tool.Utils;
import nigloo.tool.collection.WeakIdentityHashSet;

public final class Gallery
{
	private static final Path PATH_WILDCARD = Paths.get("{wildcard}");
	
	private transient Path rootFolder;
	
	private List<Artist> artists = new ArrayList<>();
	private List<Image> images = new ArrayList<>();
	private FileFolderOrder defaultSortOrder = new FileFolderOrder(SortBy.NAME, 0, true);
	@JsonAdapter(SortOrderSerializer.class)
	private Map<Path, FileFolderOrder> sortOrder = new HashMap<>();
	private SlideShowParameters slideShowParameter = new SlideShowParameters();
	
	private transient long nextId = 1;
	transient WeakIdentityHashSet<ImageReference> allImageReferences = new WeakIdentityHashSet<>();
	
	/*
	 * Need to be called just after deserialization
	 */
	public void finishConstruct()
	{
		nextId = images.stream().mapToLong(Image::getId).max().orElse(0) + 1;
		
		for (Artist artist : artists)
			for (FanboxDownloader autoDownloader : artist.getAutodownloaders())
				autoDownloader.setArtist(artist);
	}
	
	public Path getRootFolder()
	{
		return rootFolder;
	}
	
	public void setRootFolder(Path rootFolder)
	{
		if (this.rootFolder != null)
			throw new IllegalStateException("rootFolder already set (" + rootFolder + ")");
		
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
	
	public FileFolderOrder getDefaultSortOrder()
	{
		return defaultSortOrder;
	}
	
	public void setDefaultSortOrder(FileFolderOrder defaultSortOrder)
	{
		this.defaultSortOrder = defaultSortOrder;
	}
	
	public FileFolderOrder getSortOrder(Path path)
	{
		if (path == null)
			return defaultSortOrder;
		
		path = toRelativePath(path);
		
		FileFolderOrder order = sortOrder.get(path);
		return order != null ? order : getSubDirectoriesSortOrder(path.getParent());
	}
	
	public void setSortOrder(Path path, FileFolderOrder order)
	{
		if (order == null)
			sortOrder.remove(toRelativePath(path));
		else
			sortOrder.put(toRelativePath(path), order);
	}
	
	public boolean isOrderInherited(Path path)
	{
		return !sortOrder.containsKey(toRelativePath(path));
	}
	
	public FileFolderOrder getSubDirectoriesSortOrder(Path path)
	{
		if (path == null)
			return defaultSortOrder;
		
		path = toRelativePath(path);
		
		FileFolderOrder order = sortOrder.get(path.resolve(PATH_WILDCARD));
		
		return order != null ? order : getSortOrder(path);
	}
	
	public void setSubDirectoriesSortOrder(Path path, FileFolderOrder order)
	{
		if (order == null)
			sortOrder.remove(toRelativePath(path).resolve(PATH_WILDCARD));
		else
			sortOrder.put(toRelativePath(path).resolve(PATH_WILDCARD), order);
	}
	
	public boolean isSubDirectoriesOrderInherited(Path path)
	{
		return !sortOrder.containsKey(toRelativePath(path).resolve(PATH_WILDCARD));
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
	
	static private class SortOrderSerializer
	        implements JsonSerializer<Map<Path, FileFolderOrder>>, JsonDeserializer<Map<Path, FileFolderOrder>>
	{
		//@formatter:off
		private static final String JSON_PATH_WILDCARD = "*";
		private static final Type SERIALIZED_MAP_TYPE = new TypeToken<Map<String, FileFolderOrder>>(){}.getType();
		//@formatter:on
		
		@Override
		public JsonElement serialize(Map<Path, FileFolderOrder> map, Type typeOfSrc, JsonSerializationContext context)
		{
			if (map == null)
				return context.serialize(Map.of());
			else
			{
				return context.serialize(map.entrySet()
				                            .stream()
				                            .filter(e -> e.getKey() != null)
				                            .filter(e -> e.getValue() != null)
				                            .collect(Collectors.toMap(e -> e.getKey()
				                                                            .toString()
				                                                            .replace(PATH_WILDCARD.toString(),
				                                                                     JSON_PATH_WILDCARD.toString()),
				                                                      Entry::getValue,
				                                                      (v1, v2) -> v1,
				                                                      () -> new TreeMap<>(Utils.NATURAL_ORDER))),
				                         SERIALIZED_MAP_TYPE);
			}
		}
		
		@Override
		public Map<Path, FileFolderOrder> deserialize(JsonElement json,
		                                              Type typeOfT,
		                                              JsonDeserializationContext context)
		        throws JsonParseException
		{
			Map<String, FileFolderOrder> map = context.deserialize(json, SERIALIZED_MAP_TYPE);
			
			return map.entrySet()
			          .stream()
			          .filter(e -> e.getKey() != null)
			          .filter(e -> e.getValue() != null)
			          .collect(Collectors.toMap(e -> Paths.get(e.getKey()
			                                                    .replace(JSON_PATH_WILDCARD.toString(),
			                                                             PATH_WILDCARD.toString())),
			                                    Entry::getValue,
			                                    (v1, v2) -> v1,
			                                    HashMap::new));
		}
	}
}
