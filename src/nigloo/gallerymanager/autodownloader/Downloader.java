package nigloo.gallerymanager.autodownloader;

import java.io.IOException;
import java.lang.Character.UnicodeBlock;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
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
import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
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
import nigloo.gallerymanager.ui.dialog.DownloadsProgressViewDialog;
import nigloo.tool.MetronomeTimer;
import nigloo.tool.StrongReference;
import nigloo.tool.Utils;
import nigloo.tool.http.DownloadListener;
import nigloo.tool.http.MonitorBodyHandler;
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
		register("PATREON", PatreonDownloader.class);
	}
	
	@Inject
	protected transient Gallery gallery;
	@Inject
	private transient DownloadsProgressViewDialog downloadsProgressView;
	
	protected transient Artist artist;
	
	protected String creatorId;
	protected ZonedDateTime mostRecentPostCheckedDate = null;
	protected long minDelayBetweenRequests = 0;
	protected Pattern titleFilterRegex = null;
	
	protected ImagesConfiguration imageConfiguration;
	protected FilesConfiguration fileConfiguration;
	
	@JsonAdapter(value = MappingTypeAdapter.class, nullSafe = false)
	private Mapping mapping = new Mapping();
	
	protected Downloader()
	{
		Injector.init(this);
	}
	
	protected Downloader(String creatorId)
	{
		this();
		this.creatorId = creatorId;
	}
	
	@Override
	public String toString()
	{
		return creatorId + " (" + (artist == null ? "no_artist" : artist.getName()) + ") from "
		        + CLASS_TO_TYPE.get(getClass());
	}
	
	public final CompletableFuture<?> download(Properties secrets, DownloadOption... options)
	{
		LOGGER.info("Download for {} with pattern {}",
		            this,
		            Optional.ofNullable(imageConfiguration)
		                    .map(ImagesConfiguration::getPathPattern)
		                    .or(() -> Optional.ofNullable(fileConfiguration).map(FilesConfiguration::getPathPattern))
		                    .orElse("[none]"));
		
		DownloadImages downloadImages = Optional.ofNullable(imageConfiguration).map(ImagesConfiguration::getDownload).orElse(DownloadImages.NO);
		DownloadFiles downloadFiles = Optional.ofNullable(fileConfiguration).map(FilesConfiguration::getDownload).orElse(DownloadFiles.NO);
		
		DownloadSession session = new DownloadSession(secrets, options);
		downloadsProgressView.newSession(session.id, this.toString());
		
		try
		{
			onStartDownload(session);
			Iterator<Post> postIt = listPosts(session);
			
			final List<Post> postsToDownload = new ArrayList<>();
			final Collection<CompletableFuture<?>> postsFutures = new ArrayList<>();
			
			while (postIt.hasNext())
			{
				Post post = postIt.next();
				if (session.stopCheckingPost(post.publishedDatetime()))
					break;
				
				if (titleFilterRegex != null && !titleFilterRegex.matcher(post.title()).matches())
					continue;
				
				postsToDownload.add(post);
				session.postDownloadResult.put(post, DownloadSession.PostDownloadResult.NOT_CHECKED);
			}
			// Download from oldest to newest post so we can save our progress in case of a failure
			postsToDownload.sort(Comparator.comparing(Post::publishedDatetime));
			
			for (Post post : postsToDownload)
			{
				downloadsProgressView.newPost(session.id, post.id(), post.title(), post.publishedDatetime());
				
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
				
				postsFutures.add(Utils.observe(postFuture, (r, error) ->
				{
					if (error != null)
						LOGGER.error("Error downloading post " + post + " for " + creatorId + " (" + artist.getName()
						        + ") from " + CLASS_TO_TYPE.get(getClass()), error);
					
					session.onPostDownloaded(post, error);
				}));
			}
			
			return Utils.observe(CompletableFuture.allOf(postsFutures.toArray(CompletableFuture[]::new)),
			                     (r, error) ->
			                     {
				                     session.onSessionEnd();
				                     downloadsProgressView.endSession(session.id, error);
			                     });
		}
		catch (Exception e)
		{
			downloadsProgressView.endSession(session.id, e);
			return CompletableFuture.failedFuture(e);
		}
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
	
	public record FileKey(String postId, String fileId) implements Comparable<FileKey>
	{
		public FileKey
		{
			Objects.requireNonNull(postId, "postId");
			Objects.requireNonNull(fileId, "fileId");
		}
		
		@Override
		public int compareTo(FileKey o)
		{
			int res = Utils.NATURAL_ORDER.compare(postId, o.postId);
			return (res != 0) ? res : Utils.NATURAL_ORDER.compare(fileId, o.fileId);
		}
	}
	
	private static class Mapping
	{
		private final Map<ImageKey, ImageReference> imageMapping = new HashMap<>();
		private final Map<FileKey, ImageReference> imageFileMapping = new HashMap<>();
		private final Map<FileKey, Map<String, ImageReference>> zipMapping = new HashMap<>();
		
		public boolean contains(ImageKey imageKey)
		{
			synchronized (imageMapping)
			{
				return imageMapping.containsKey(imageKey);
			}
		}
		
		public boolean contains(FileKey fileKey)
		{
			synchronized (imageFileMapping)
			{
				if(imageFileMapping.containsKey(fileKey))
					return true;
			}
			synchronized (zipMapping)
			{
				return zipMapping.containsKey(fileKey);
			}
		}
		
		public ImageReference get(ImageKey imageKey)
		{
			synchronized (imageMapping)
			{
				return imageMapping.get(imageKey);
			}
		}
		
		public void put(ImageKey imageKey, Image image)
		{
			synchronized (imageMapping)
			{
				imageMapping.put(imageKey, new ImageReference(image));
			}
		}
		
		public Optional<ImageReference> getFileAsImageMapping(FileKey fileKey)
		{
			synchronized (imageFileMapping)
			{
				return imageFileMapping.containsKey(fileKey)
						? Optional.ofNullable(imageFileMapping.get(fileKey))
						: null;
			}
		}
		
		public void put(FileKey fileKey, Image image)
		{
			synchronized (imageFileMapping)
			{
				imageFileMapping.put(fileKey, new ImageReference(image));
			}
			synchronized (zipMapping)
			{
				zipMapping.remove(fileKey);
			}
		}
		
		public void putEmptyMapping(FileKey fileKey)
		{
			synchronized (zipMapping)
			{
				zipMapping.put(fileKey, new TreeMap<>(Utils.NATURAL_ORDER));
			}
			synchronized (imageFileMapping)
			{
				imageFileMapping.remove(fileKey);
			}
		}
		
		public void put(FileKey fileKey, String pathInZip, Image image)
		{
			synchronized (zipMapping)
			{
				zipMapping.computeIfAbsent(fileKey, k -> new TreeMap<>(Utils.NATURAL_ORDER))
				          .put(pathInZip, new ImageReference(image));
			}
			synchronized (imageFileMapping)
			{
				imageFileMapping.remove(fileKey);
			}
		}
		
		public boolean isHandling(Image image)
		{
			synchronized (imageMapping)
			{
				for (ImageReference ref : imageMapping.values())
					if (ref != null && ref.getImage().equals(image))
						return true;
			}
			synchronized (imageFileMapping)
			{
				for (ImageReference ref : imageFileMapping.values())
					if (ref != null && ref.getImage().equals(image))
						return true;
			}
			synchronized (zipMapping)
			{
				for (Map<String, ImageReference> zipEntry : zipMapping.values())
					if (zipEntry != null)
						for (ImageReference ref : zipEntry.values())
							if (ref != null && ref.getImage().equals(image))
								return true;
			}
			return false;
		}
		
		public void markDeleted(Collection<Image> images)
		{
			Set<Long> imageIds = images.stream().map(Image::getId).collect(Collectors.toSet());
			
			synchronized (imageMapping)
			{
				for (Entry<ImageKey, ImageReference> entry : imageMapping.entrySet())
					if (entry.getValue() != null && imageIds.contains(entry.getValue().getImageId()))
						entry.setValue(null);
			}
			synchronized (imageFileMapping)
			{
				for (Entry<FileKey, ImageReference> entry : imageFileMapping.entrySet())
					if (entry.getValue() != null && imageIds.contains(entry.getValue().getImageId()))
						entry.setValue(null);
			}
			synchronized (zipMapping)
			{
				for (Entry<FileKey, Map<String, ImageReference>> entry : zipMapping.entrySet())
					if (entry.getValue() != null)
						for (Entry<String, ImageReference> zipEntry : entry.getValue().entrySet())
							if (imageIds.contains(zipEntry.getValue().getImageId()))
								zipEntry.setValue(null);
			}
		}
	}

	public static class ImagesConfiguration
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
	
	public static class FilesConfiguration
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

		public AutoExtractZip getAutoExtractZip()
		{
			return autoExtractZip;
		}

		public void setAutoExtractZip(AutoExtractZip autoExtractZip)
		{
			this.autoExtractZip = autoExtractZip;
		}
	}
	
	protected record Post(String id, String title, ZonedDateTime publishedDatetime, Object extraInfo) {
		public static Post create(String id, String title, ZonedDateTime publishedDatetime, Object extraInfo) {
			if (Utils.isBlank(id))
				throw new IllegalArgumentException("id cannot be empty");
			if (Utils.isBlank(title))
				title = "";
			if (publishedDatetime == null)
				throw new IllegalArgumentException("publishedDatetime cannot be null");
			
			return new Post(id, title, publishedDatetime, extraInfo);
		}
	}
	protected record PostImage(String id, String filename, String url, Collection<String> tags) {
		public static PostImage create(String id, String filename, String url, Collection<String> tags) {
			String newFilename = validate(id, filename, url, tags);
			return new PostImage(id, newFilename, url, tags);
		}
	}
	protected record PostFile(String id, String filename, String url, Collection<String> tags) {
		public static PostFile create(String id, String filename, String url, Collection<String> tags) {
			String newFilename = validate(id, filename, url, tags);
			return new PostFile(id, newFilename, url, tags);
		}
	}
	
	static private String validate(String id, String filename, String url, Collection<String> tags) {
		if (Utils.isBlank(id))
			throw new IllegalArgumentException("Id cannot be empty");
		
		URL parsedURL;
		try {
			parsedURL = new URL(url);
		}
		catch (MalformedURLException e) {
			throw new IllegalArgumentException("Bad URL \"" + url + "\"", e);
		}
		
		boolean badFilename = Utils.isBlank(filename);
		try {
			new URL(filename);
			badFilename = true;
		} catch (MalformedURLException ignored){}
		// If the filename is bad, extract one for the URL
		if (badFilename) {
			filename = Paths.get(parsedURL.getPath()).getFileName().toString();
		}
		
		return filename;
	}
	
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
	
	public static class HttpException extends RuntimeException
	{
		private final URI requestUri;
		private final int statusCode;
		private final HttpHeaders headers;
		private final Object body;
		
		private String prettyBody = null;
		
		private HttpException(HttpResponse<?> response)
		{
			super("Error "+response.statusCode()+" from "+response.request().uri());
			requestUri = response.request().uri();
			statusCode = response.statusCode();
			headers = response.headers();
			body = response.body();
		}
		
		public String getPrettyBody()
		{
			if (prettyBody == null)
			{
				prettyBody = prettyToString(body);
			}
			
			return prettyBody;
		}
		
		// @formatter:off
		public URI getRequestUri() {return requestUri;}
		public int getStatusCode() {return statusCode;}
		public HttpHeaders getHeaders() {return headers;}
		public Object getBody() {return body;}
		// @formatter:on
	}
	
	protected final class DownloadSession
	{
		private static final AtomicLong NEXT_ID = new AtomicLong(1L);
		
		private final long id = NEXT_ID.getAndIncrement();
		
		private final HttpClient httpClient = HttpClient.newBuilder()
		                                                .followRedirects(Redirect.NORMAL)
		                                                .executor(AsyncPools.HTTP_REQUEST)
		                                                .build();
		// TODO init with max_concurrent_streams from http2
		private final Semaphore maxConcurrentStreams = new Semaphore(10);
		private final MetronomeTimer requestLimiter = (minDelayBetweenRequests > 0) ? new MetronomeTimer(minDelayBetweenRequests) : null;
		private final Map<Post, PostDownloadResult> postDownloadResult = Collections.synchronizedMap(new IdentityHashMap<>());
		
		private enum PostDownloadResult {
			NOT_CHECKED,
			SUCCESS,
			ERROR
		}
		
		private final List<Image> imagesAdded = new ArrayList<>();
		private final Map<String, Object> extraInfo = Collections.synchronizedMap(new HashMap<>());
		
		private final Properties secrets;
		private final EnumSet<DownloadOption> options;
		
		public DownloadSession(Properties secrets, DownloadOption[] options)
		{
			this.secrets = secrets;
			this.options = EnumSet.noneOf(DownloadOption.class);
			if (options != null)
				this.options.addAll(List.of(options));
		}
		
		public boolean has(DownloadOption option)
		{
			return options.contains(option);
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
			return send(request, responseBodyHandler, null);
		}
		
		public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler, DownloadListener listener)
		        throws IOException,
		        InterruptedException
		{
			logRequest(request);
			HttpResponse<T> response = null;
			maxConcurrentStreams.acquire();
			if (requestLimiter != null)
				requestLimiter.waitNextTick();
			if (listener != null)
				responseBodyHandler = new MonitorBodyHandler<>(responseBodyHandler, listener);
			try
			{
				response = httpClient.send(request, MoreBodyHandlers.decoding(responseBodyHandler));
			}
			finally
			{
				maxConcurrentStreams.release();
			}
			logResponse(response);
			
			if (isErrorResponse(response))
				throw new HttpException(response);
			
			return response;
		}
		
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler)
		        throws InterruptedException
		{
			return sendAsync(request, responseBodyHandler, null);
		}
		
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler, DownloadListener listener)
		        throws InterruptedException
		{
			logRequest(request);
			maxConcurrentStreams.acquire();
			if (requestLimiter != null)
				requestLimiter.waitNextTick();
			if (listener != null)
				responseBodyHandler = new MonitorBodyHandler<>(responseBodyHandler, listener);
			return Utils.observe(httpClient.sendAsync(request, MoreBodyHandlers.decoding(responseBodyHandler)),
			                     (response, error) ->
			                     {
				                     maxConcurrentStreams.release();
				                     if (error == null)
					                     logResponse(response);
			                     })
			            .thenCompose(response -> isErrorResponse(response)
			                    ? CompletableFuture.failedFuture(new HttpException(response))
			                    : CompletableFuture.completedFuture(response));
		}
		
		private boolean stopCheckingPost(ZonedDateTime publishedDatetime)
		{
			synchronized (Downloader.this)
			{
				if (mostRecentPostCheckedDate == null || options.contains(DownloadOption.CHECK_ALL_POST))
					return false;
				
				int comp = publishedDatetime.compareTo(mostRecentPostCheckedDate);
				
				// publishedDatetime before mostRecentPostCheckedDate
				if (comp < 0)
					return true;
				
				// publishedDatetime == mostRecentPostCheckedDate
				if (comp == 0) {
					// Stop check only if not MIDNIGHT (no hour or minutes)
					// Otherwise it means the precision is only up to the day and we might miss a post.
					return publishedDatetime.toLocalTime().isAfter(LocalTime.MIDNIGHT);
				}
				
				// publishedDatetime after mostRecentPostCheckedDate
				return false;
			}
		}
		
		private void onPostDownloaded(Post post, Throwable error)
		{
			postDownloadResult.put(post, error != null ? PostDownloadResult.ERROR : PostDownloadResult.SUCCESS);
			downloadsProgressView.endPost(id, post.id(), error);
		}
		
		private void onSessionEnd()
		{
			synchronized (Downloader.this)
			{
				Optional<ZonedDateTime> firstBadPostDate = postDownloadResult.entrySet()
				                                                             .stream()
				                                                             .filter(e -> e.getValue() != PostDownloadResult.SUCCESS)
				                                                             .map(Entry::getKey)
				                                                             .map(Post::publishedDatetime)
				                                                             .min(Comparator.naturalOrder());
				
				mostRecentPostCheckedDate = postDownloadResult.entrySet()
				                                              .stream()
				                                              .filter(e -> e.getValue() == PostDownloadResult.SUCCESS)
				                                              .map(Entry::getKey)
				                                              .map(Post::publishedDatetime)
				                                              .filter(date -> firstBadPostDate.map(date::isBefore)
				                                                                              .orElse(true))
				                                              .max(Comparator.naturalOrder())
				                                              .orElse(mostRecentPostCheckedDate);
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
		final Path imageDest;
		
		synchronized (this)
		{
			if (mapping.contains(imageKey))
			{
				imageReference = mapping.get(imageKey);
				if (imageReference == null)
					return CompletableFuture.completedFuture(null);
				
				imageDest = imageReference.getImage().getAbsolutePath();
			}
			else
			{
				// @formatter:off
				imageDest = gallery.toAbsolutePath(
					new PathPatternResolver(imageConfiguration.pathPattern)
						.withCreatorId(creatorId)
						.withPost(post)
						.withImage(postImage, imageNumber)
						.resolvePath());
				// @formatter:on
				imageReference = null;
			}
			
			if (Files.exists(imageDest))
			{
				Image image;
				imageReference = mapping.get(imageKey);
				if (imageReference == null)
					image = saveImageInGallery(session, post, postImage, imageDest);
				else
				{
					image = imageReference.getImage();
					if (session.has(DownloadOption.UPDATE_IMAGES_ALREADY_DOWNLOADED))
					{
						saveImageInGallery(session, post, postImage, imageDest);
					}
				}
				
				downloadsProgressView.newExistingImage(session.id, post.id(), postImage.id(), imageDest);
				
				return CompletableFuture.completedFuture(image);
			}
		}
		
		try {
			Files.createDirectories(imageDest.getParent());
			
			StrongReference<String> contentType = new StrongReference<>();
			
			HttpRequest request = HttpRequest.newBuilder().uri(new URI(postImage.url())).GET().headers(getHeardersForImageDownload(session, postImage)).build();
			return session.sendAsync(request, new MonitorBodyHandler<>(BodyHandlers.ofFile(imageDest), new DownloadListener()
			{
				@Override
				public void onStartDownload(ResponseInfo responseInfo)
				{
					responseInfo.headers().firstValue("Content-Type").ifPresent(contentType::set);
					downloadsProgressView.newImage(session.id, post.id(), postImage.id(), imageDest);
				}
				
				@Override
				public void onProgress(long nbNewBytes, long nbBytesDownloaded, OptionalLong nbBytesTotal)
				{
					downloadsProgressView.updateDownloadProgress(session.id, post.id(), postImage.id(), nbBytesDownloaded, nbBytesTotal);
				}
				
				@Override
				public void onComplete()
				{
					downloadsProgressView.endDownload(session.id, post.id(), postImage.id(), null);
				}
				
				@Override
				public void onError(Throwable error)
				{
					downloadsProgressView.endDownload(session.id, post.id(), postImage.id(), error);
				}
			}))
			.thenApplyAsync(fixExtension(contentType, ".jpg"), AsyncPools.DISK_IO)
			.thenApply(filePath -> {
				downloadsProgressView.updateFilePath(session.id, post.id(), postImage.id(), imageDest, filePath);
				return filePath;
			})
			.thenApply(imagePath -> saveImageInGallery(session, post, postImage, imagePath));
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
		FileKey fileKey = new FileKey(post.id, file.id);
		Optional<ImageReference> mappingAsImage;
		final Path fileDest;
		
		synchronized (this)
		{
			mappingAsImage = mapping.getFileAsImageMapping(fileKey);
			
			if (mappingAsImage == null) // no mapping
			{
				// @formatter:off
				fileDest = gallery.toAbsolutePath(
					new PathPatternResolver(fileConfiguration.pathPattern)
						.withCreatorId(creatorId)
						.withPost(post)
						.withFile(file, fileNumber)
						.resolvePath());
				// @formatter:on
			}
			else if (mappingAsImage.isPresent())
			{
				fileDest = mappingAsImage.get().getImage().getAbsolutePath();
			}
			else // Deleted
			{
				return CompletableFuture.completedFuture(null);
			}
			
			if (Files.exists(fileDest))
			{
				if ((mappingAsImage == null && Image.isImage(fileDest)) || mappingAsImage.isPresent())
				{
					if (session.has(DownloadOption.UPDATE_IMAGES_ALREADY_DOWNLOADED))
					{
						saveFileAsImageInGallery(session, post, file, fileDest);
					}
					
					downloadsProgressView.newExistingImage(session.id, post.id(), file.id(), fileDest);
				}
				
				return CompletableFuture.completedFuture(null);
			}
		}
		
		try
		{
			boolean isZip = isZip(file.filename());
			
			// If the file is a zip and has a mapping (deleted or not), don't download it
			if (isZip && mapping.contains(fileKey))
				return CompletableFuture.completedFuture(null);
			
			Files.createDirectories(fileDest.getParent());
			
			StrongReference<String> contentType = new StrongReference<>();
			
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(new URI(file.url()))
			                                 .GET()
			                                 .headers(getHeardersForFileDownload(session, file))
			                                 .build();
			return session.sendAsync(request, BodyHandlers.ofFile(fileDest), new DownloadListener()
			{
				public void onStartDownload(java.net.http.HttpResponse.ResponseInfo responseInfo)
				{
					responseInfo.headers().firstValue("Content-Type").ifPresent(contentType::set);
					if (isZip)
						downloadsProgressView.newZip(session.id, post.id(), file.id(), fileDest);
					else
						downloadsProgressView.newOtherFile(session.id, post.id(), file.id(), fileDest);
				}
				
				@Override
				public void onProgress(long nbNewBytes, long nbBytesDownloaded, OptionalLong nbBytesTotal)
				{
					downloadsProgressView.updateDownloadProgress(session.id, post.id(), file.id(), nbBytesDownloaded, nbBytesTotal);
				}
				
				@Override
				public void onComplete()
				{
					downloadsProgressView.endDownload(session.id, post.id(), file.id(), null);
				}
				
				@Override
				public void onError(Throwable error)
				{
					downloadsProgressView.endDownload(session.id, post.id(), file.id(), error);
				}
			})
			.thenApplyAsync(fixExtension(contentType, null), AsyncPools.DISK_IO)
			.thenApply(filePath -> {
				downloadsProgressView.updateFilePath(session.id, post.id(), file.id(), fileDest, filePath);
				return filePath;
			})
			.thenApply(filePath -> {
				if (Image.isImage(filePath)) {
					saveFileAsImageInGallery(session, post, file, filePath);
				}
				return filePath;
			})
			.thenAccept(filePath -> unZip(session, post, file, filePath));
		}
		catch (Exception e)
		{
			return CompletableFuture.failedFuture(e);
		}
	}
	
	public static final boolean isZip(String filename)
	{
		return filename.toLowerCase(Locale.ROOT).endsWith(".zip");
	}

	private void unZip(DownloadSession session, Post post, PostFile file, Path filePath)
	{
		String filename = filePath.getFileName().toString();
		
		if (!isZip(filename) || fileConfiguration.autoExtractZip == null || fileConfiguration.autoExtractZip == AutoExtractZip.NO)
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
			
			ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath));
			ZipEntry zipEntry;
			try {
				zipEntry= zis.getNextEntry();
			} catch (Exception e1) {
				Utils.closeQuietly(zis);
				try {
					zis = new ZipInputStream(Files.newInputStream(filePath), Charset.forName("Shift-JIS"));
					zipEntry= zis.getNextEntry();
					
					String name = zipEntry.getName();
					if (name.lastIndexOf('.') >= 0)
						name = name.substring(0, name.lastIndexOf('.'));
					
					Set<UnicodeBlock> JAPANESE_UNICODE_BLOCKS = Set.of(UnicodeBlock.HIRAGANA, UnicodeBlock.KATAKANA, UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS);
					
					Map<Boolean, Long> counts = name.chars()
					                                .mapToObj(Integer::valueOf)
					                                .collect(Collectors.groupingBy(cp -> JAPANESE_UNICODE_BLOCKS.contains(UnicodeBlock.of(cp.intValue())), Collectors.counting()));
					// Less japanese character than non japanese character
					if (counts.getOrDefault(true, 0L) < counts.getOrDefault(false, 0L))
					{
						throw new IllegalArgumentException("Not japanese: " + zipEntry.getName());
					}
				} catch (Exception e2) {
					throw new IllegalArgumentException("Not suitable charset found to decode filenames in "+filePath, e2);
				}
			}
			
			mapping.putEmptyMapping(new FileKey(post.id, file.id));
			
			while (zipEntry != null)
			{
				Path entryPath = targetDirectory.resolve(makeSafe(zipEntry.getName()));
				
				if (zipEntry.isDirectory())
				{
					Files.createDirectories(entryPath);
				}
				else
				{
					Files.createDirectories(entryPath.getParent());
					Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
					
					if (Image.isImage(entryPath))
					{
						saveImageFromZipInGallery(session, post, file, zipEntry.getName(), entryPath);
						downloadsProgressView.newImageInZip(session.id, post.id, file.id, zipEntry.getName(), entryPath);
					}
					else
					{
						downloadsProgressView.newFileInZip(session.id, post.id, file.id, zipEntry.getName(), entryPath);
					}
				}
				zipEntry = zis.getNextEntry();
			}
			zis.close();
			
			Files.delete(filePath);
		}
		catch (Exception e)
		{
			LOGGER.error("Error when unzipping " + filePath, e);
			mapping.zipMapping.remove(new FileKey(post.id, file.id));
		}
	}
	
	private static final Set<Integer> FORBIDDEN_CHARS = "\\/:*?\"<>|".codePoints()
	                                                                 .mapToObj(cp -> (int) cp)
	                                                                 .collect(Collectors.toUnmodifiableSet());
	
	private static final Path makeSafe(String path)
	{
		int len = path.length();
		StringBuilder safePath = new StringBuilder(len);
		
		int begin = 0;
		while(true)
		{
			// Skip leading spaces and forbidden characters
			int cp;
			while (begin < len && (Character.isWhitespace(cp = path.codePointAt(begin)) || FORBIDDEN_CHARS.contains(cp)))
				begin++;
			
			// Find the end of the current "name" (file/folder name)
			int endName = begin;
			while (endName < len && (cp = path.codePointAt(endName)) != '/' && cp != '\\')
				endName++;
			
			// Skip trailing spaces, forbidden characters and dots (windows silently remove them otherwise)
			int end = endName;
			while (end > 0 && (Character.isWhitespace(cp = path.codePointAt(end - 1)) || FORBIDDEN_CHARS.contains(cp) || cp == '.'))
				end--;
			
			// Append name
			if (begin <= end)
			{
				// No need to call strip/trim because the first and last characters are guaranteed to be valid.
				safePath.append(removeForbiddenCharacters(path.substring(begin, end)));
			}
			
			// Append separator
			if (endName < len)
			{
				safePath.append(path.charAt(endName));
				begin = endName + 1;
			}
			else // No separator = last name
				break;
		}
		
		return Paths.get(safePath.toString());
	}
	
	private static String removeForbiddenCharacters(String string)
	{
		StringBuilder sb = new StringBuilder(string.length());
		string.codePoints().filter(cp -> !FORBIDDEN_CHARS.contains(cp)).forEachOrdered(sb::appendCodePoint);
		return sb.toString();
	}
	
	private static class PathPatternResolver
	{
		private final String pattern;
		private final Map<String, String> variables = new HashMap<>();
		
		public PathPatternResolver(String pattern)
		{
			this.pattern = pattern;
		}

		private PathPatternResolver withValue(String var, String value)
		{
			variables.put(Objects.requireNonNull(var, "var cannot be null"),
			              Objects.requireNonNull(value, "value for " + var + " cannot be null"));
			return this;
		}
		
		public Path resolvePath()
		{
			String path = pattern;
			
			for (Entry<String, String> entry : variables.entrySet())
			{
				// remove forbidden character BEFORE replacing in the path so / and \ don't mess up everything
				path = path.replace('{'+entry.getKey()+'}', removeForbiddenCharacters(entry.getValue()));
			}
			
			// Still call make to remove leading and trailing spaces in file/folder names
			return makeSafe(path);
		}
		
		public PathPatternResolver withCreatorId(String creatorId)
		{
			return withValue("creatorId", creatorId);
		}
		
		public PathPatternResolver withPost(Post post)
		{
			// @formatter:off
			return withValue("postId", post.id)
			      .withValue("postDate", DateTimeFormatter.ISO_LOCAL_DATE.format(post.publishedDatetime))
			      .withValue("postTitle", post.title);
			// @formatter:on
		}
		
		public PathPatternResolver withImage(PostImage image, int imageNumber)
		{
			// @formatter:off
			return withValue("imageFilename", image.filename)
			      .withValue("imageNumber", String.format("%02d", imageNumber));
			// @formatter:on
		}
		
		public PathPatternResolver withFile(PostFile file, int fileNumber)
		{
			// @formatter:off
			return withValue("fileId", file.id)
			      .withValue("filename", file.filename)
			      .withValue("fileNumber", String.format("%02d", fileNumber));
			// @formatter:on
		}
	}
	
	private static boolean isErrorResponse(HttpResponse<?> response)
	{
		return response.statusCode() >= 400;
	}
	
	private Image saveImageInGallery(DownloadSession session, Post post, PostImage postImage, Path path)
	{
		Image image = doSaveInGallery(session, postImage.tags(), path);
		mapping.put(new ImageKey(post.id(), postImage.id()), image);
		return image;
	}
	
	private Image saveFileAsImageInGallery(DownloadSession session, Post post, PostFile postFile, Path path)
	{
		Image image = doSaveInGallery(session, postFile.tags(), path);
		mapping.put(new FileKey(post.id(), postFile.id()), image);
		return image;
	}
	
	private Image saveImageFromZipInGallery(DownloadSession session, Post post, PostFile postFile, String pathInZip, Path path)
	{
		Image image = doSaveInGallery(session, postFile.tags(), path);
		mapping.put(new FileKey(post.id(), postFile.id()), pathInZip, image);
		return image;
	}
	
	private Image doSaveInGallery(DownloadSession session, Collection<String> tags, Path path)
	{
		Image image = gallery.getImage(path);
		if (image.isNotSaved())
		{
			image.addTag(artist.getTag());
			addTags(image, tags);
			
			gallery.saveImage(image);
			session.imagesAdded.add(image);
		}
		else if (session.has(DownloadOption.UPDATE_IMAGES_ALREADY_DOWNLOADED))
		{
			image.getTags().forEach(image::removeTag);
			image.addTag(artist.getTag());
			addTags(image, tags);
		}
		
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
	
	private Function<HttpResponse<Path>, Path> fixExtension(StrongReference<String> contentType, String defaultExtention)
	{
		return response -> {
			Path path = response.body();
			String filename = path.getFileName().toString();
			String extension = Utils.getExtention(filename);
			// Already have an extension, nothing to do.
			if (extension != null)
				return path;
			
			TikaConfig config = TikaConfig.getDefaultConfig();
			String mediaType = contentType.get();
			
			// Do the detection. Use DefaultDetector / getDetector() for more advanced detection
			Metadata metadata = new Metadata();
			metadata.set(Metadata.CONTENT_TYPE, mediaType);
			try (TikaInputStream stream = TikaInputStream.get(path, metadata)) {
				mediaType = config.getMimeRepository().detect(stream, metadata).toString();
			}
			catch (IOException e) {
				throw new RuntimeException("Cannot detect media type of "+path, e);
			}
			
			try {
				// Fetch the most common extension for the detected type
				MimeType mimeType = config.getMimeRepository().forName(mediaType);
				extension = mimeType.getExtension();
			}
			catch (MimeTypeException e) {}
			
			if (Utils.isBlank(extension)) { // mimeType.getExtension() can return blank
				if (defaultExtention == null) {
					return path;
				}
				
				LOGGER.warn("Not suitable extension found for " + path + ". Defaulted to " + defaultExtention);
				extension = defaultExtention;
			}
			
			try {
				return Files.move(path, path.resolveSibling(filename + extension));
			}
			catch (IOException e) {
				throw new RuntimeException("Cannot add the extension " + extension + " to " + path, e);
			}
		};
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
	
	static class MappingTypeAdapter extends TypeAdapter<Mapping>
	{
		static private final String DELETED_FILE = "deleted";
		
		@Override
		public void write(JsonWriter out, Mapping mapping) throws IOException
		{
			if (mapping == null)
				mapping = new Mapping();
			
			synchronized (mapping.imageMapping) {
			synchronized (mapping.imageFileMapping) {
			synchronized (mapping.zipMapping) {
				
				List<Entry<?, ?>> bothMappingEntries = new ArrayList<>(mapping.imageMapping.size()
				        + mapping.imageFileMapping.size() + mapping.zipMapping.size());
				bothMappingEntries.addAll(mapping.imageMapping.entrySet());
				bothMappingEntries.addAll(mapping.imageFileMapping.entrySet());
				bothMappingEntries.addAll(mapping.zipMapping.entrySet());
				
				bothMappingEntries.sort((e1, e2) ->
				{
					String postId1;
					int order1;
					String fileId1;
					if (e1.getKey() instanceof ImageKey ik1)
					{
						postId1 = ik1.postId;
						order1 = 0;
						fileId1 = ik1.imageId;
					}
					else if (e1.getKey() instanceof FileKey fk1)
					{
						postId1 = fk1.postId;
						order1 = 1;
						fileId1 = fk1.fileId;
					}
					else
					{
						throw new IllegalStateException("Unknown mapping key type: " + e1.getKey());
					}
					
					String postId2;
					int order2;
					String fileId2;
					if (e2.getKey() instanceof ImageKey ik2)
					{
						postId2 = ik2.postId;
						order2 = 0;
						fileId2 = ik2.imageId;
					}
					else if (e2.getKey() instanceof FileKey fk2)
					{
						postId2 = fk2.postId;
						order2 = 1;
						fileId2 = fk2.fileId;
					}
					else
					{
						throw new IllegalStateException("Unknown mapping key type: " + e2.getKey());
					}
					
					// Last post first
					int res = Utils.NATURAL_ORDER.compare(postId1, postId2) * -1;
					if (res != 0)
						return res;
					// Image mapping then file/zip mapping
					res = Integer.compare(order1, order2);
					if (res != 0)
						return res;
					// Order by image/file id
					return Utils.NATURAL_ORDER.compare(fileId1, fileId2);
				});
				
				out.beginArray();
				
				for (Entry<?, ?> entry : bothMappingEntries)
				{
					out.beginObject();
					
					if (entry.getKey() instanceof ImageKey imageKey)
					{
						ImageReference ref = (ImageReference) entry.getValue();
						
						out.name("postId");
						out.value(imageKey.postId);
						out.name("imageId");
						out.value(imageKey.imageId);
						out.name("imageRef");
						if (ref == null)
							out.value(DELETED_FILE);
						else
							out.value(ref.getImageId());
						
					}
					else if (entry.getKey() instanceof FileKey fileKey)
					{
						out.name("postId");
						out.value(fileKey.postId);
						out.name("fileId");
						out.value(fileKey.fileId);
						
						if (entry.getValue() instanceof ImageReference ref)
						{
							out.name("imageRef");
							if (ref == null)
								out.value(DELETED_FILE);
							else
								out.value(ref.getImageId());
						}
						else // Zip entries
						{
							@SuppressWarnings("unchecked")
							Map<String, ImageReference> zipEntries = (Map<String, ImageReference>) entry.getValue();
							
							out.name("zipEntries");
							if (zipEntries == null)
								out.value(DELETED_FILE);
							else
							{
								out.beginObject();
								for (Entry<String, ImageReference> zipEntry : zipEntries.entrySet()
								                                                        .stream()
								                                                        .sorted(Comparator.comparing(Entry::getKey))
								                                                        .toList())
								{
									ImageReference ref = zipEntry.getValue();
									
									out.name(zipEntry.getKey());
									if (ref == null)
										out.value(DELETED_FILE);
									else
										out.value(ref.getImageId());
								}
								out.endObject();
							}
						}
					}
					else
					{
						throw new IllegalStateException("Bad mapping entry: " + entry);
					}
					
					out.endObject();
				}
			
			}}}// En of synchronized blocks
			
			out.endArray();
		}
		
		@Override
		public Mapping read(JsonReader in) throws IOException
		{
			// We create the instance and no-one else knows about it, no synchronization required
			Mapping mapping = new Mapping();
			
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
				String fileId = null;
				Map<String, ImageReference> zipEntries = null;
				
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
						case "postId" ->
						{
							postId = in.nextString();
						}
						case "imageId" ->
						{
							imageId = in.nextString();
						}
						case "imageRef" ->
						{
							if (in.peek() == JsonToken.NUMBER)
								ref = new ImageReference(in.nextLong());
							else
								in.skipValue();
						}
						case "fileId" ->
						{
							fileId = in.nextString();
						}
						case "zipEntries" ->
						{
							if (in.peek() == JsonToken.BEGIN_OBJECT)
							{
								in.beginObject();
								zipEntries = new TreeMap<>(Utils.NATURAL_ORDER);
								while (in.peek() != JsonToken.END_OBJECT)
								{
									String pathInZip = in.nextName();
									ImageReference imageRef = null;
									if (in.peek() == JsonToken.NUMBER)
										imageRef = new ImageReference(in.nextLong());
									else
										in.skipValue();
									
									zipEntries.put(pathInZip, imageRef);
								}
								in.endObject();
							}
							else
								in.skipValue();
						}
						default -> in.skipValue();
					}
				}
				
				in.endObject();
				
				if (imageId != null)
				{
					mapping.imageMapping.put(new ImageKey(postId, imageId), ref);
				}
				else if (fileId != null)
				{
					FileKey fileKey = new FileKey(postId, fileId);
					
					if (ref != null)
						mapping.imageFileMapping.put(fileKey, ref);
					else if (zipEntries != null)
						mapping.zipMapping.put(new FileKey(postId, fileId), zipEntries);
					else if (ref == null && zipEntries == null)
						mapping.imageFileMapping.put(fileKey, null);
				}
			}
			
			in.endArray();
			
			return mapping;
		}
	}
	
	public final boolean isHandling(Image image)
	{
		return mapping.isHandling(image);
	}
	
	public final void markDeleted(Collection<Image> images)
	{
		mapping.markDeleted(images);
	}
	
	public final Artist getArtist()
	{
		return artist;
	}
	
	public final void setArtist(Artist artist)
	{
		this.artist = artist;
	}
	
	public final ImagesConfiguration getImageConfiguration()
	{
		return imageConfiguration;
	}

	public final void setImageConfiguration(ImagesConfiguration imageConfiguration)
	{
		this.imageConfiguration = imageConfiguration;
	}

	public final FilesConfiguration getFileConfiguration()
	{
		return fileConfiguration;
	}

	public final void setFileConfiguration(FilesConfiguration fileConfiguration)
	{
		this.fileConfiguration = fileConfiguration;
	}
}
