package nigloo.gallerymanager.autodownloader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jsoup.Jsoup;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import nigloo.gallerymanager.AsyncPools;
import nigloo.gallerymanager.autodownloader.Downloader.FilesConfiguration.AutoExtractZip;
import nigloo.gallerymanager.autodownloader.Downloader.FilesConfiguration.DownloadFiles;
import nigloo.gallerymanager.autodownloader.Downloader.ImagesConfiguration.DownloadImages;
import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.ImageReference;
import nigloo.gallerymanager.model.Tag;
import nigloo.tool.MetronomeTimer;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

@JsonAdapter(Downloader.DownloaderAdapter.class)
public abstract class Downloader
{
	private static final Logger LOGGER = LogManager.getLogger(Downloader.class);
	
	private static final Marker HTTP_REQUEST = MarkerManager.getMarker("HTTP_REQUEST");
	private static final Marker HTTP_REQUEST_URL = MarkerManager.getMarker("HTTP_REQUEST_URL").setParents(HTTP_REQUEST);
	private static final Marker HTTP_REQUEST_HEADERS = MarkerManager.getMarker("HTTP_REQUEST_HEADERS")
	                                                                .setParents(HTTP_REQUEST);
	
	private static final Marker HTTP_RESPONSE = MarkerManager.getMarker("HTTP_RESPONSE");
	private static final Marker HTTP_RESPONSE_URL = MarkerManager.getMarker("HTTP_RESPONSE_URL")
	                                                             .setParents(HTTP_RESPONSE);
	private static final Marker HTTP_RESPONSE_STATUS = MarkerManager.getMarker("HTTP_RESPONSE_STATUS")
	                                                                .setParents(HTTP_RESPONSE);
	private static final Marker HTTP_RESPONSE_HEADERS = MarkerManager.getMarker("HTTP_RESPONSE_HEADERS")
	                                                                 .setParents(HTTP_RESPONSE);
	private static final Marker HTTP_RESPONSE_BODY = MarkerManager.getMarker("HTTP_RESPONSE_BODY")
	                                                              .setParents(HTTP_RESPONSE);
	
	private static final Map<String, Class<? extends Downloader>> TYPE_TO_CLASS = new HashMap<>();
	private static final Map<Class<? extends Downloader>, String> CLASS_TO_TYPE = new HashMap<>();
	
	public static void register(String type, Class<? extends Downloader> clazz)
	{
		TYPE_TO_CLASS.put(type, clazz);
		CLASS_TO_TYPE.put(clazz, type);
	}
	
	static
	{
		register("FANBOX", FanboxDownloader.class);
		register("PIXIV", PixivDownloader.class);
		register("TWITTER", TwitterDownloader.class);
		register("MASONRY", MasonryDownloader.class);
	}
	
	@Inject
	protected transient Gallery gallery;
	
	protected transient Artist artist;
	
	protected String creatorId;
	protected ZonedDateTime mostRecentPostCheckedDate = null;
	protected long minDelayBetweenRequests = 0;
	protected Pattern titleFilterRegex = null;
	
	protected ImagesConfiguration imageConfiguration;
	protected FilesConfiguration fileConfiguration;
	
	@JsonAdapter(value = MappingTypeAdapter.class, nullSafe = false)
	private Map<ImageKey, ImageReference> mapping = new LinkedHashMap<>();
	// TODO replace by Map<ImageKey, DownloadedItem> where DownloadedItem can be a
	// wrapped Image referere or a ZipFile (containing an internal map pathInZip ->
	// Imagereference for each image (Image.isImage))
	
	protected Downloader()
	{
		Injector.init(this);
	}
	
	protected Downloader(String creatorId)
	{
		this();
		this.creatorId = creatorId;
	}
	
	public final Artist getArtist()
	{
		return artist;
	}
	
	public final void setArtist(Artist artist)
	{
		this.artist = artist;
	}
	
	public final void download(Properties secrets, boolean checkAllPost) throws Exception
	{
		LOGGER.info("Download for {} ({}) from {} with pattern {}",
		            creatorId,
		            artist.getName(),
		            CLASS_TO_TYPE.get(getClass()),
		            imageConfiguration != null && imageConfiguration.pathPattern != null
		                    ? imageConfiguration.pathPattern
		                    : fileConfiguration != null && fileConfiguration.pathPattern != null
		                            ? fileConfiguration.pathPattern
		                            : "[none]");
		
		DownloadSession session = new DownloadSession(secrets, checkAllPost);
		
		onStartDownload(session);
		Iterator<Post> postIt = listPosts(session);
		
		final Collection<CompletableFuture<?>> postsDownloads = new ArrayList<>();
		while (postIt.hasNext())
		{
			Post post = postIt.next();
			
			if (session.stopCheckingPost(post.publishedDatetime()))
				break;
			
			if (titleFilterRegex != null && !titleFilterRegex.matcher(post.title()).matches())
				continue;
			
			DownloadImages downloadImages = imageConfiguration != null && imageConfiguration.download != null ? imageConfiguration.download : DownloadImages.NO;
			DownloadFiles downloadFiles = fileConfiguration != null && fileConfiguration.download != null ? fileConfiguration.download : DownloadFiles.NO;
			
			CompletableFuture<List<PostImage>> listImagesFuture = (downloadImages == DownloadImages.YES ||
			                                                       downloadImages == DownloadImages.IF_NO_FILES ||
			                                                       downloadFiles == DownloadFiles.IF_NO_IMAGES)
			                ? listImages(session, post)
			                : null;
			
			CompletableFuture<List<PostFile>> listFilesFuture = (downloadFiles == DownloadFiles.YES ||
			                                                     downloadFiles == DownloadFiles.IF_NO_IMAGES ||
			                                                     downloadImages == DownloadImages.IF_NO_FILES)
			                ? listFiles(session, post)
			                : null;
			
			CompletableFuture<?> downloadImagesFuture = switch (downloadImages)
			{
				case YES -> listImagesFuture.thenCompose(images -> downloadImages(session, post, images));
				case NO -> null;
				case IF_NO_FILES -> listFilesFuture.thenCompose(files -> files.isEmpty()
				        ? listImagesFuture.thenCompose(images -> downloadImages(session, post, images))
				        : CompletableFuture.completedFuture(null));
			};
			
			CompletableFuture<?> downloadFilesFuture = switch (downloadFiles)
			{
				case YES -> listFilesFuture.thenCompose(files -> downloadFiles(session, post, files));
				case NO -> null;
				case IF_NO_IMAGES -> listImagesFuture.thenCompose(images -> images.isEmpty()
				        ? listFilesFuture.thenCompose(files -> downloadFiles(session, post, files))
				        : CompletableFuture.completedFuture(null));
			};
			
			CompletableFuture<?> postFuture;
			
			if (downloadImagesFuture != null && downloadFilesFuture != null)
			{
				postFuture = CompletableFuture.allOf(downloadImagesFuture, downloadFilesFuture);
			}
			else if (downloadImagesFuture != null)
			{
				postFuture = downloadImagesFuture;
			}
			else if (downloadFilesFuture != null)
			{
				postFuture = downloadFilesFuture;
			}
			else
			{
				postFuture = CompletableFuture.completedFuture(null);
			}
			
			postsDownloads.add(postFuture.thenRun(() -> session.onPostDownloaded(post)));
		}
		
		CompletableFuture.allOf(postsDownloads.toArray(CompletableFuture[]::new))
		                 .thenRun(() -> session.onSessionEnd())
		                 .join();
	}
	
	public record ImageKey(String postId, String imageId) implements Comparable<ImageKey>
	{
		public ImageKey
		{
			Objects.requireNonNull(postId, "postId");
			Objects.requireNonNull(imageId, "imageId");
		}
		
		@Override
		public int compareTo(ImageKey o)
		{
			int res = Utils.NATURAL_ORDER.compare(postId, o.postId);
			return (res != 0) ? res : Utils.NATURAL_ORDER.compare(imageId, o.imageId);
		}
	}
	
	protected static class ImagesConfiguration
	{
		public enum DownloadImages
		{
			YES,
			NO,
			IF_NO_FILES
		}
		
		private DownloadImages download;
		private String pathPattern;
		
		public DownloadImages getDownload()
		{
			return download;
		}
		
		public void setDownload(DownloadImages download)
		{
			this.download = download;
		}
		
		public String getPathPattern()
		{
			return pathPattern;
		}
		
		public void setPathPattern(String pathPattern)
		{
			this.pathPattern = pathPattern;
		}
	}
	
	protected static class FilesConfiguration
	{
		public enum DownloadFiles
		{
			YES,
			NO,
			IF_NO_IMAGES
		}
		
		public enum AutoExtractZip
		{
			NO, SAME_DIRECTORY, NEW_DIRECTORY
		}
		
		private DownloadFiles download;
		private String pathPattern;
		private AutoExtractZip autoExtractZip;
		
		public DownloadFiles getDownload()
		{
			return download;
		}
		
		public void setDownload(DownloadFiles download)
		{
			this.download = download;
		}
		
		public String getPathPattern()
		{
			return pathPattern;
		}
		
		public void setPathPattern(String pathPattern)
		{
			this.pathPattern = pathPattern;
		}
	}
	
	protected record Post(String id, String title, ZonedDateTime publishedDatetime, Object extraInfo){}
	protected record PostImage(String id, String filename, String url, Collection<String> tags) {}
	protected record PostFile(String id, String filename, String url, Collection<String> tags) {}
	
	protected void onStartDownload(DownloadSession session) throws Exception {}
	
	protected abstract Iterator<Post> listPosts(DownloadSession session) throws Exception;
	
	protected abstract CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post) throws Exception;
	
	protected abstract String[] getHeardersForImageDownload(DownloadSession session, PostImage image);
	
	protected CompletableFuture<List<PostFile>> listFiles(DownloadSession session, Post post) throws Exception
	{
		return CompletableFuture.completedFuture(List.of());
	}
	
	protected String[] getHeardersForFileDownload(DownloadSession session, PostFile image)
	{
		throw new UnsupportedOperationException(getClass().getSimpleName()+".getHeardersForFileDownload");
	}
	
	protected final class DownloadSession
	{
		private final HttpClient httpClient = HttpClient.newBuilder()
		                                                .followRedirects(Redirect.NORMAL)
		                                                .executor(AsyncPools.HTTP_REQUEST)
		                                                .build();
		// TODO init with max_concurrent_streams from http2
		private final Semaphore maxConcurrentStreams = new Semaphore(10);
		private final MetronomeTimer requestLimiter = (minDelayBetweenRequests > 0) ? new MetronomeTimer(minDelayBetweenRequests) : null;
		private ZonedDateTime currentMostRecentPost = mostRecentPostCheckedDate;
		
		private final List<Image> imagesAdded = new ArrayList<>();
		private final Map<String, Object> extraInfo = Collections.synchronizedMap(new HashMap<>());
		
		private final Properties secrets;
		private final boolean checkAllPost;
		
		public DownloadSession(Properties secrets, boolean checkAllPost)
		{
			this.secrets = secrets;
			this.checkAllPost = checkAllPost;
		}
		
		public String getSecret(String key)
		{
			return secrets.getProperty(key);
		}
		
		public void setExtaInfo(String key, Object value)
		{
			extraInfo.put(key, value);
		}
		
		public <T> T getExtraInfo(String key)
		{
			return Utils.cast(extraInfo.get(key));
		}
		
		public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler)
		        throws IOException,
		        InterruptedException
		{
			logRequest(request);
			HttpResponse<T> response = null;
			maxConcurrentStreams.acquire();
			if (requestLimiter != null)
				requestLimiter.waitNextTick();
			try
			{
				response = httpClient.send(request, MoreBodyHandlers.decoding(responseBodyHandler));
			}
			finally
			{
				maxConcurrentStreams.release();
			}
			logResponse(response);
			return response;
		}
		
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler)
		        throws InterruptedException
		{
			logRequest(request);
			maxConcurrentStreams.acquire();
			if (requestLimiter != null)
				requestLimiter.waitNextTick();
			return httpClient.sendAsync(request, MoreBodyHandlers.decoding(responseBodyHandler))
			                 .handle((response, error) ->
			                 {
				                 maxConcurrentStreams.release();
				                 if (error != null)
					                 return CompletableFuture.<HttpResponse<T>>failedFuture(error);
				                 else
				                 {
					                 logResponse(response);
					                 return CompletableFuture.completedFuture(response);
				                 }
			                 })
			                 .thenCompose(f -> f);
		}
		
		private boolean stopCheckingPost(ZonedDateTime publishedDatetime)
		{
			synchronized (Downloader.this)
			{
				return mostRecentPostCheckedDate != null && publishedDatetime.compareTo(mostRecentPostCheckedDate) <= 0
				        && !checkAllPost;
			}
		}
		
		private void onPostDownloaded(Post post)
		{
			synchronized (this)
			{
				if (currentMostRecentPost == null || currentMostRecentPost.isBefore(post.publishedDatetime()))
					currentMostRecentPost = post.publishedDatetime();
			}
		}
		
		private void onSessionEnd()
		{
			synchronized (Downloader.this)
			{
				if (currentMostRecentPost != null && (mostRecentPostCheckedDate == null
				        || mostRecentPostCheckedDate.isBefore(currentMostRecentPost)))
					mostRecentPostCheckedDate = currentMostRecentPost;
			}
		}
	}
	
	protected static abstract class BasePostIterator implements Iterator<Post>
	{
		protected final DownloadSession session;
		private Post nextPost;
		
		public BasePostIterator(DownloadSession session)
		{
			this.session = session;
		}

		protected abstract Post findNextPost() throws Exception;
		
		protected final void computeNextPost() throws Exception
		{
			nextPost = findNextPost();
		}
		
		@Override
		public final boolean hasNext()
		{
			return nextPost != null;
		}

		@Override
		public final Post next()
		{
			if (!hasNext())
				throw new NoSuchElementException();
			
			Post post = nextPost;
			try
			{
				computeNextPost();
			}
			catch (Exception e)
			{
				throw Utils.asRunTimeException(e);
			}
			
			return post;
		}
	}
	
	private CompletableFuture<?> downloadImages(DownloadSession session, Post post, List<PostImage> images)
	{
		ArrayList<CompletableFuture<?>> imagesDownloads = new ArrayList<>();
		
		int imageNumber = 1;
		for (PostImage image : images)
		{
			imagesDownloads.add(downloadImage(session,
			                                  post,
			                                  image,
			                                  imageNumber));
			imageNumber++;
		}
		
		return CompletableFuture.allOf(imagesDownloads.toArray(CompletableFuture[]::new));
	}

	private CompletableFuture<Image> downloadImage(DownloadSession session,
	                                               Post post,
	                                               PostImage postImage,
	                                               int imageNumber)
	{
		ImageKey imageKey = new ImageKey(post.id(), postImage.id());
		ImageReference imageReference;
		Path imageDest;
		
		synchronized (this)
		{
			if (mapping.containsKey(imageKey))
			{
				imageReference = mapping.get(imageKey);
				if (imageReference == null)
					return CompletableFuture.completedFuture(null);
				
				imageDest = imageReference.getImage().getAbsolutePath();
			}
			else
			{
				imageDest = Paths.get(imageConfiguration.pathPattern.replace("{creatorId}", creatorId)
				                                                    .replace("{postId}", post.id())
				                                                    .replace("{postDate}",
				                                                             DateTimeFormatter.ISO_LOCAL_DATE.format(post.publishedDatetime()))
				                                                    .replace("{postTitle}", post.title())
				                                                    .replace("{imageNumber}",
				                                                             String.format("%02d", imageNumber))
				                                                    .replace("{imageFilename}", postImage.filename()));
				imageDest = makeSafe(gallery.toAbsolutePath(imageDest));
				imageReference = null;
			}
			
			if (Files.exists(imageDest))
			{
				Image image;
				imageReference = mapping.get(imageKey);
				if (imageReference == null)
					image = saveInGallery(session, post.id(), postImage.id(), postImage.tags(), imageDest);
				else
					image = imageReference.getImage();
				saveInGallery(session, post.id(), postImage.id(), postImage.tags(), image.getAbsolutePath());//TODO remove update tags on saved images
				
				return CompletableFuture.completedFuture(image);
			}
		}
		
		try {
			Files.createDirectories(imageDest.getParent());
			
			HttpRequest request = HttpRequest.newBuilder().uri(new URI(postImage.url())).GET().headers(getHeardersForImageDownload(session, postImage)).build();
			return session.sendAsync(request, BodyHandlers.ofFile(imageDest))
			              .thenApply(saveInGallery(session, post.id(), postImage.id(), postImage.tags()));
		}
		catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private CompletableFuture<?> downloadFiles(DownloadSession session, Post post, List<PostFile> files)
	{
		ArrayList<CompletableFuture<?>> filesDownloads = new ArrayList<>();
		
		int fileNumber = 1;
		for (PostFile file : files)
		{
			filesDownloads.add(downloadFile(session,
			                                post,
			                                file,
			                                fileNumber));
			fileNumber++;
		}
		
		return CompletableFuture.allOf(filesDownloads.toArray(CompletableFuture[]::new));
	}

	private CompletableFuture<?> downloadFile(DownloadSession session,
	                                          Post post,
	                                          PostFile file,
	                                          int fileNumber)
	{
		HttpRequest request = null;
		Path fileDest = null;
		try
		{
			fileDest = Paths.get(fileConfiguration.pathPattern.replace("{creatorId}", creatorId)
			                                                  .replace("{postId}", post.id())
			                                                  .replace("{postDate}",
			                                                           DateTimeFormatter.ISO_LOCAL_DATE.format(post.publishedDatetime()))
			                                                  .replace("{postTitle}", post.title())
			                                                  .replace("{fileNumber} ",
			                                                           String.format("%02d", fileNumber))
			                                                  .replace("{filename}", file.filename()));
			fileDest = makeSafe(gallery.toAbsolutePath(fileDest));
			
			if (Files.exists(fileDest))
				return CompletableFuture.completedFuture(null);
			
			Files.createDirectories(fileDest.getParent());
			
			request = HttpRequest.newBuilder().uri(new URI(file.url())).GET().headers(getHeardersForFileDownload(session, file)).build();
			return session.sendAsync(request, BodyHandlers.ofFile(fileDest))
			              .thenAccept(response -> unZip(response.body()));
		}
		catch (Exception e)
		{
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private void unZip(Path filePath)
	{
		String filename = filePath.getFileName().toString();
		
		if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip") || fileConfiguration.autoExtractZip == null || fileConfiguration.autoExtractZip == AutoExtractZip.NO)
			return;
		
		LOGGER.debug("Unziping: " + filePath);
		
		try
		{
			Path targetDirectory = switch (fileConfiguration.autoExtractZip)
			{
				case NO -> throw new IllegalStateException(Objects.toString(fileConfiguration.autoExtractZip));
				case SAME_DIRECTORY -> filePath.getParent();
				case NEW_DIRECTORY -> filePath.resolveSibling(filename.substring(0, filename.length() - ".zip".length()));
			};
		
			Files.createDirectories(targetDirectory);
			//FIXME check the entry name for charset (exception of not enough "good chars = bad => go to next charset)
			//, Support utf8 and japanses charset
			ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath));//, Charset.forName("Shift-JIS"));
			ZipEntry zipEntry = zis.getNextEntry();
			
			
			
			while (zipEntry != null)
			{
				Path entryPath = targetDirectory.resolve(zipEntry.getName());
				
				if (zipEntry.isDirectory())
				{
					Files.createDirectories(entryPath);
				}
				else
				{
					Files.createDirectories(entryPath.getParent());
					Files.copy(zis, entryPath);
					
					if (Image.isImage(entryPath))
					{
						Image image = gallery.getImage(entryPath);
						if (image.isNotSaved())
						{
							image.addTag(artist.getTag());
							gallery.saveImage(image);
						}
					}
				}
				zipEntry = zis.getNextEntry();
			}
			zis.closeEntry();
			zis.close();
			
			Files.delete(filePath);
		}
		catch (Exception e)
		{
			LOGGER.error("Error when unzipping " + filePath, e);
		}
	}
	
	private static final Path makeSafe(Path path)
	{
		Path newPath = path.getRoot();
		
		for (int i = 0 ; i < path.getNameCount() ; i++)
		{
			newPath = newPath.resolve(path.getName(i).toString().trim());
		}
		
		return newPath;
	}
	
	private static boolean isErrorResponse(HttpResponse<?> response)
	{
		return response.statusCode() >= 300;
	}
	
	private synchronized Image saveInGallery(DownloadSession session, String postId, String imageId, Collection<String> tags, Path path)
	{
		Image image = gallery.getImage(path);
		if (image.isNotSaved())
		{
			image.addTag(artist.getTag());
			addTags(image, tags);
			
			gallery.saveImage(image);
			session.imagesAdded.add(image);
		}
		else {//TODO remove update tags on saved images
		image.getTags().forEach(image::removeTag);
		image.addTag(artist.getTag());
		addTags(image, tags);
		}
		
		ImageReference ref = new ImageReference(image);
		
		ImageKey imagekey = new ImageKey(postId, imageId);
		mapping.put(imagekey, ref);
		
		return image;
	}
	
	private void addTags(Image image, Collection<String> tags)
	{
		if (tags == null)
			return;
		
		for (String tagName : tags)
		{
			Tag tag = gallery.findTag(tagName);
			if (tag == null)
			{
				tag = gallery.getTag(tagName);
				tag.setParents(List.of(gallery.getTag("unchecked_tag")));
			}
			
			image.addTag(tag);
		}
	}
	
	private Function<HttpResponse<Path>, Image> saveInGallery(DownloadSession session, String postId, String imageId, Collection<String> tags)
	{
		return response -> saveInGallery(session, postId, imageId, tags, response.body());
	}
	
	private static void logRequest(HttpRequest request)
	{
		LOGGER.debug(HTTP_REQUEST_URL, "Request: {}", request.uri());
		LOGGER.debug(HTTP_REQUEST_HEADERS,
		             "Headers: {}",
		             () -> request.headers()
		                          .map()
		                          .entrySet()
		                          .stream()
		                          .map(e -> "    " + e.getKey() + ": " + e.getValue())
		                          .collect(Collectors.joining("\n")));
	}
	
	private static <T> void logResponse(HttpResponse<T> response)
	{
		Level level = isErrorResponse(response) ? Level.ERROR : Level.DEBUG;
		if (LOGGER.isEnabled(level, HTTP_RESPONSE))
		{
			synchronized (LOGGER)
			{
				
				LOGGER.log(level, HTTP_RESPONSE_URL, "Response: {}", response.request().uri());
				LOGGER.log(level, HTTP_RESPONSE_STATUS, "Status: {}", response.statusCode());
				LOGGER.log(level,
				           HTTP_RESPONSE_HEADERS,
				           "Headers: {}",
				           () -> response.headers()
				                         .map()
				                         .entrySet()
				                         .stream()
				                         .map(e -> "    " + e.getKey() + ": " + e.getValue())
				                         .collect(Collectors.joining("\n")));
				LOGGER.log(level, HTTP_RESPONSE_BODY, "Body: {}", () -> prettyToString(response.body()));
			}
		}
	}
	
	private static String prettyToString(Object obj)
	{
		if (obj == null)
			return null;
		
		String str = obj.toString();
		
		try
		{
			return new GsonBuilder().setPrettyPrinting()
			                        .disableHtmlEscaping()
			                        .serializeNulls()
			                        .create()
			                        .toJson(JsonParser.parseString(str));
		}
		catch (Exception ignored)
		{
		}
		
		try
		{
			return Jsoup.parseBodyFragment(str).selectFirst("body").toString();
		}
		catch (Exception ignored)
		{
		}
		
		return str;
	}
	
	@SuppressWarnings("unused")
	private static String[] parseHeader(String rawHeader)
	{
		return rawHeader.lines().filter(s -> !s.isBlank()).flatMap(s ->
		{
			int i = s.indexOf(':');
			return Stream.of(s.substring(0, i).trim(), s.substring(i + 1).trim());
		}).toArray(String[]::new);
	}
	
	protected static Map<String, HttpCookie> parseCookies(String header)
	{
		return HttpCookie.parse(header).stream().collect(Collectors.toMap(HttpCookie::getName, c -> c, (c1, c2) -> c1, () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));
	}
	
	@SuppressWarnings("unused")
	private static class FormUrlEncodedBodyPublisher implements BodyPublisher
	{
		private Map<String, String> parameters = new LinkedHashMap<>();
		private BodyPublisher delegate = null;
		
		public FormUrlEncodedBodyPublisher put(String key, String value)
		{
			if (delegate != null)
				throw new IllegalStateException("Cannot add parameter: subscribe or contentLength already called");
			
			parameters.put(key, URLEncoder.encode(value, StandardCharsets.UTF_8));
			return this;
		}
		
		private void initDelegate()
		{
			if (delegate != null)
				return;
			
			delegate = BodyPublishers.ofString(parameters.entrySet()
			                                             .stream()
			                                             .map(p -> p.getKey() + "=" + p.getValue())
			                                             .collect(Collectors.joining("&")));
		}
		
		@Override
		public void subscribe(Subscriber<? super ByteBuffer> subscriber)
		{
			initDelegate();
			delegate.subscribe(subscriber);
		}
		
		@Override
		public long contentLength()
		{
			initDelegate();
			return delegate.contentLength();
		}
	}
	
	public static class DownloaderAdapter
	        implements JsonSerializer<Downloader>, JsonDeserializer<Downloader>
	{
		@Override
		public JsonElement serialize(Downloader src, Type typeOfSrc, JsonSerializationContext context)
		{
			Class<?> clazz = src.getClass();
			String type = CLASS_TO_TYPE.get(clazz);
			if (type == null)
				throw new IllegalStateException("No type registered for " + clazz);
			
			JsonObject out = new JsonObject();
			out.addProperty("type", type);
			
			for (Entry<String, JsonElement> e : context.serialize(src).getAsJsonObject().entrySet())
				out.add(e.getKey(), e.getValue());
			
			return out;
		}
		
		@Override
		public Downloader deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
		        throws JsonParseException
		{
			String type = json.getAsJsonObject().get("type").getAsString();
			Class<?> clazz = TYPE_TO_CLASS.get(type);
			if (clazz == null)
				throw new IllegalStateException("No class registered for " + type);
			
			return context.deserialize(json, clazz);
		}
	}
	
	static class MappingTypeAdapter extends TypeAdapter<Map<ImageKey, ImageReference>>
	{
		static private final String DELETED_IMAGE = "deleted";
		
		@Override
		public void write(JsonWriter out, Map<ImageKey, ImageReference> value) throws IOException
		{
			if (value == null)
				value = Collections.emptyMap();
			
			out.beginArray();
			
			for (Entry<ImageKey, ImageReference> entry : value.entrySet()
			                                                  .stream()
			                                                  .sorted(Comparator.comparing((Entry<ImageKey, ImageReference> e) -> e.getKey())
			                                                                    .reversed())
			                                                  .toList())
			{
				ImageKey imageKey = entry.getKey();
				ImageReference ref = entry.getValue();
				
				out.beginObject();
				out.name("postId");
				out.value(imageKey.postId);
				out.name("imageId");
				out.value(imageKey.imageId);
				out.name("imageRef");
				if (ref == null)
					out.value(DELETED_IMAGE);
				else
					out.value(ref.getImageId());
				out.endObject();
			}
			
			out.endArray();
		}
		
		@Override
		public Map<ImageKey, ImageReference> read(JsonReader in) throws IOException
		{
			Map<ImageKey, ImageReference> mapping = new LinkedHashMap<>();
			
			if (in.peek() == JsonToken.NULL)
			{
				in.nextNull();
				return mapping;
			}
			
			in.beginArray();
			
			while (in.peek() != JsonToken.END_ARRAY)
			{
				if (in.peek() == JsonToken.NULL)
				{
					in.nextNull();
					continue;
				}
				
				String postId = null;
				String imageId = null;
				ImageReference ref = null;
				
				in.beginObject();
				
				while (in.peek() != JsonToken.END_OBJECT)
				{
					String property = in.nextName();
					if (in.peek() == JsonToken.NULL)
					{
						in.nextNull();
						continue;
					}
					
					switch (property)
					{
						case "postId":
							postId = in.nextString();
							break;
						case "imageId":
							imageId = in.nextString();
							break;
						case "imageRef":
							if (in.peek() == JsonToken.NUMBER)
								ref = new ImageReference(in.nextLong());
							else
								in.skipValue();
							break;
						default:
							in.skipValue();
							break;
					}
				}
				
				in.endObject();
				
				mapping.put(new ImageKey(postId, imageId), ref);
			}
			
			in.endArray();
			
			return mapping;
		}
	}
	
	public final boolean isHandling(Image image)
	{
		for (ImageReference ref : mapping.values())
			if (ref != null && ref.getImage().equals(image))
				return true;
			
		return false;
	}
	
	public final void stopHandling(Collection<Image> images)
	{
		Map<Image, ImageKey> imageToKey = mapping.entrySet()
		                                         .stream()
		                                         .filter(e -> e.getValue() != null)
		                                         .collect(Collectors.toMap(e -> e.getValue().getImage(),
		                                                                   e -> e.getKey()));
		
		for (Image image : images)
		{
			ImageKey key = imageToKey.get(image);
			if (key != null)
				mapping.put(key, null);
		}
	}
}
