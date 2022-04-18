package nigloo.gallerymanager.model;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;

import nigloo.gallerymanager.autodownloader.Downloader;
import nigloo.tool.Utils;
import nigloo.tool.collection.WeakIdentityHashSet;

public final class Gallery
{
	private static final Logger LOGGER = LogManager.getLogger(Gallery.class);
	private static final Path PATH_WILDCARD = Paths.get("{wildcard}");
	
	private transient Path rootFolder;
	
	private List<Artist> artists = new ArrayList<>();
	private List<Image> images = new ArrayList<>();
	private List<Tag> tags = new ArrayList<>();
	private FileFolderOrder defaultSortOrder = new FileFolderOrder(SortBy.NAME, 0, true);
	@JsonAdapter(SortOrderSerializer.class)
	private Map<Path, FileFolderOrder> sortOrder = new HashMap<>();
	private SlideShowParameters slideShowParameter = new SlideShowParameters();
	private GalleryScripts scripts = new GalleryScripts();
	
	private transient long nextId = 1;
	transient WeakIdentityHashSet<ImageReference> allImageReferences = new WeakIdentityHashSet<>();
	transient WeakIdentityHashSet<TagReference> allTagReferences = new WeakIdentityHashSet<>();
	
	static public class GalleryScripts
	{
		private String afterGalleryLoad;

		public String getAfterGalleryLoad()
		{
			return afterGalleryLoad;
		}

		public void setAfterGalleryLoad(String afterGalleryLoad)
		{
			this.afterGalleryLoad = afterGalleryLoad;
		}
	}
	
	/*
	 * Need to be called just after deserialization
	 */
	public void finishConstruct()
	{
		nextId = images.stream().mapToLong(Image::getId).max().orElse(0) + 1;
		
		for (Artist artist : artists)
			for (Downloader autoDownloader : artist.getAutodownloaders())
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
		synchronized (images)
		{
			return images.stream().filter(image -> image.getId() == imageId).findAny().orElse(null);
		}
	}
	
	/**
	 * Return null if not found
	 * 
	 * @param path
	 * @param includeUnsaved if true, also searching among unsaved images
	 * @return the image
	 */
	public Image findImage(Path path, boolean includeUnsaved)
	{
		synchronized (images)
		{
			final Path relPath = toRelativePath(path);
			
			return imagesStream(includeUnsaved).filter(image -> image.getPath().equals(relPath)).findAny().orElse(null);
		}
	}
	
	public Collection<Image> findImagesIn(Path path, boolean includeUnsaved)
	{
		synchronized (images)
		{
			final Path absPath = toAbsolutePath(path);
			
			return imagesStream(includeUnsaved).filter(image -> image.getAbsolutePath().startsWith(absPath)).toList();
		}
	}
	
	/**
	 * Return an unsaved image if not found
	 * 
	 * @param path
	 * @return
	 */
	public Image getImage(Path path)
	{
		synchronized (images)
		{
			final Path relPath = toRelativePath(path);
			
			return images.stream()
			             .filter(image -> image.getPath().equals(relPath))
			             .findAny()
			             .orElseGet(() -> unsavedImages().computeIfAbsent(relPath, p -> new Image(relPath)));
		}
	}
	
	public void saveImage(Image image)
	{
		if (image.isSaved())
			return;
		
		synchronized (images)
		{
			unsavedImages().remove(image.getPath());
			
			image.id = nextId++;
			images.add(image);
		}
		LOGGER.info("Image saved in gallery: {}", image.getPath());
	}
	
	private transient Map<Path, Image> unsavedImages = new HashMap<>();
	transient boolean unsavedImagesValid = true;
	
	private Map<Path, Image> unsavedImages()
	{
		if (!unsavedImagesValid)
		{
			unsavedImages = unsavedImages.values()
			                             .stream()
			                             .collect(Collectors.toMap(i -> i.getPath(),
			                                                       i -> i,
			                                                       (i1, i2) -> i1,
			                                                       HashMap::new));
			unsavedImagesValid = true;
		}
		return unsavedImages;
	}
	
	public void deleteImages(Collection<Image> images)
	{
		List<Image> deleted = new ArrayList<>(images.size());
		synchronized (this.images)
		{
			for (Artist artist : artists)
				for (Downloader autoDownloader : artist.getAutodownloaders())
					autoDownloader.stopHandling(images);
			
			// This last or we break every ImageReference
			for (Image image : images)
				if (this.images.remove(image))
					deleted.add(image);
			
			unsavedImages().keySet().removeAll(images.stream().map(Image::getPath).toList());
		}
		for (Image image : deleted)
			LOGGER.info("Image deleted from gallery: {}", image.getPath());
	}
	
	public Collection<Image> getImages(boolean includeUnsaved)
	{
		synchronized (images)
		{
			return imagesStream(includeUnsaved).toList();
		}
	}
	
	private Stream<Image> imagesStream(boolean includeUnsaved)
	{
		return includeUnsaved ? Stream.concat(images.stream(), unsavedImages().values().stream()) : images.stream();
	}
	
	public Tag findTag(String tagName)
	{
		synchronized (tags)
		{
			return tags.stream().filter(tag -> tag.getName().equals(tagName)).findAny().orElse(null);
		}
	}
	
	public Tag getTag(String tagName)
	{
		String nTagName = Tag.normalize(tagName);
		synchronized (tags)
		{
			return tags.stream().filter(tag -> tag.getName().equals(nTagName)).findAny().orElseGet(() ->
			{
				Tag newTag = new Tag(nTagName);
				tags.add(newTag);
				return newTag;
			});
		}
	}
	
	public List<Tag> getTags()
	{
		return Collections.unmodifiableList(tags);
	}
	
	public List<Artist> getArtists()
	{
		return artists;
	}
	
	public FileFolderOrder getDefaultSortOrder()
	{
		synchronized (sortOrder)
		{
			return defaultSortOrder;
		}
	}
	
	public void setDefaultSortOrder(FileFolderOrder defaultSortOrder)
	{
		synchronized (sortOrder)
		{
			this.defaultSortOrder = defaultSortOrder;
		}
	}
	
	public FileFolderOrder getSortOrder(Path path)
	{
		synchronized (sortOrder)
		{
			if (path == null)
				return defaultSortOrder;
			
			path = toRelativePath(path);
			
			FileFolderOrder order = sortOrder.get(path);
			return order != null ? order : getSubDirectoriesSortOrder(path.getParent());
		}
	}
	
	public void setSortOrder(Path path, FileFolderOrder order)
	{
		synchronized (sortOrder)
		{
			if (order == null)
				sortOrder.remove(toRelativePath(path));
			else
				sortOrder.put(toRelativePath(path), order);
		}
	}
	
	public boolean isOrderInherited(Path path)
	{
		synchronized (sortOrder)
		{
			return !sortOrder.containsKey(toRelativePath(path));
		}
	}
	
	public FileFolderOrder getSubDirectoriesSortOrder(Path path)
	{
		synchronized (sortOrder)
		{
			if (path == null)
				return defaultSortOrder;
			
			path = toRelativePath(path);
			
			FileFolderOrder order = sortOrder.get(path.resolve(PATH_WILDCARD));
			
			return order != null ? order : getSortOrder(path);
		}
	}
	
	public void setSubDirectoriesSortOrder(Path path, FileFolderOrder order)
	{
		synchronized (sortOrder)
		{
			if (order == null)
				sortOrder.remove(toRelativePath(path).resolve(PATH_WILDCARD));
			else
				sortOrder.put(toRelativePath(path).resolve(PATH_WILDCARD), order);
		}
	}
	
	public boolean isSubDirectoriesOrderInherited(Path path)
	{
		synchronized (sortOrder)
		{
			return !sortOrder.containsKey(toRelativePath(path).resolve(PATH_WILDCARD));
		}
	}
	
	public SlideShowParameters getSlideShowParameter()
	{
		return slideShowParameter;
	}
	
	public GalleryScripts getScripts()
	{
		return scripts;
	}

	public void move(Path source, Path target)
	{
		final Path fSource = toAbsolutePath(source);
		final Path fTarget = toAbsolutePath(target);
		
		synchronized (images)
		{
			synchronized (sortOrder)
			{
				Collection<Image> allImages = getImages(true);
				Map<Path, Image> pathToImage = allImages.stream()
				                                        .collect(Collectors.toMap(i -> i.getAbsolutePath(), i -> i));
				
				for (Image image : allImages)
				{
					Path path = image.getAbsolutePath();
					if (path.startsWith(fSource))
					{
						Path newPath = fTarget.resolve(fSource.relativize(path));
						Image existing = pathToImage.get(newPath);
						if (existing != null)
						{
							images.remove(existing);
							unsavedImages().remove(existing.getPath());
						}
						
						image.move(toRelativePath(newPath));
					}
				}
				
				Map<Path, Path> mapping = new HashMap<>();
				for (Path path : sortOrder.keySet())
				{
					path = toAbsolutePath(path);
					if (path.startsWith(fSource))
						mapping.put(path, fTarget.resolve(fSource.relativize(path)));
				}
				
				for (Entry<Path, Path> entry : mapping.entrySet())
					sortOrder.put(toRelativePath(entry.getValue()), sortOrder.remove(toRelativePath(entry.getKey())));
			}
		}
	}
	
	public void compactIds()
	{
		synchronized (images)
		{
//			java.util.Map<Path, java.nio.file.attribute.FileTime> time = new java.util.HashMap<>(images.size());
//			for (Image image : images)
//				try
//				{
//					time.put(image.getPath(), java.nio.file.Files.getLastModifiedTime(toAbsolutePath(image.getPath())));
//				}
//				catch (java.io.IOException e)
//				{
//					e.printStackTrace();
//				}
//			images.sort(java.util.Comparator.comparing(i -> time.get(i.getPath())));
			
			// Force all references to load their image so updating image.id will update
			// the reference
			for (ImageReference ref : allImageReferences)
				ref.getImage();
			
			nextId = 1;
			for (Image image : images)
				image.id = nextId++;
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
				                                                                     JSON_PATH_WILDCARD),
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
			                                                    .replace(JSON_PATH_WILDCARD, PATH_WILDCARD.toString())),
			                                    Entry::getValue,
			                                    (v1, v2) -> v1,
			                                    HashMap::new));
		}
	}
}
