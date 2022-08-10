package nigloo.gallerymanager.autodownloader;

import java.io.IOException;
import java.lang.Character.UnicodeBlock;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.net.URI;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
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
	//FIXME make mapping thread-safe
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
		            imageConfiguration != null && imageConfiguration.pathPattern != null
		                    ? imageConfiguration.pathPattern
		                    : fileConfiguration != null && fileConfiguration.pathPattern != null
		                            ? fileConfiguration.pathPattern
		                            : "[none]");
		
		DownloadImages downloadImages = imageConfiguration != null && imageConfiguration.download != null ? imageConfiguration.download : DownloadImages.NO;
		DownloadFiles downloadFiles = fileConfiguration != null && fileConfiguration.download != null ? fileConfiguration.download : DownloadFiles.NO;
		
		DownloadSession session = new DownloadSession(secrets, options);
		downloadsProgressView.newSession(session.id, this.toString());
		
		try
		{
			onStartDownload(session);
			Iterator<Post> postIt = listPosts(session);
			//TODO download posts from oldest to newest
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
			}
			
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
		private final List<PostDownloadResult> postHandledSuccess = new ArrayList<>();
		private boolean lastPostToCheckReached = false;
		
		private record PostDownloadResult(ZonedDateTime postPublishedDatetime, boolean success) {}
		
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
				lastPostToCheckReached = mostRecentPostCheckedDate != null
				        && publishedDatetime.compareTo(mostRecentPostCheckedDate) <= 0
				        && !options.contains(DownloadOption.CHECK_ALL_POST);
				return lastPostToCheckReached;
			}
		}
		
		private void onPostDownloaded(Post post, Throwable error)
		{
			synchronized (this)
			{
				postHandledSuccess.add(new PostDownloadResult(post.publishedDatetime(), error == null));
			}
			
			downloadsProgressView.endPost(id, post.id(), error);
		}
		
		private void onSessionEnd()
		{
			synchronized (Downloader.this)
			{
				if (!lastPostToCheckReached)
					return;
				
				Optional<ZonedDateTime> firstBadPostDate = postHandledSuccess.stream()
				                                                             .filter(r -> !r.success())
				                                                             .map(r -> r.postPublishedDatetime())
				                                                             .min(Comparator.naturalOrder());
				mostRecentPostCheckedDate = postHandledSuccess.stream()
				                                              .filter(r -> r.success())
				                                              .map(r -> r.postPublishedDatetime())
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
			if (mapping.containsKey(imageKey))
			{
				imageReference = mapping.get(imageKey);
				if (imageReference == null)
					return CompletableFuture.completedFuture(null);
				
				imageDest = imageReference.getImage().getAbsolutePath();
			}
			else
			{
				// @formatter:off
				imageDest = gallery.toAbsolutePath(makeSafe(
					imageConfiguration.pathPattern.replace("{creatorId}", creatorId)
					                              .replace("{postId}", post.id())
					                              .replace("{postDate}", DateTimeFormatter.ISO_LOCAL_DATE.format(post.publishedDatetime()))
					                              .replace("{postTitle}",  post.title())
					                              .replace("{imageNumber}", String.format("%02d", imageNumber))
					                              .replace("{imageFilename}", postImage.filename())));
				// @formatter:on
				imageReference = null;
			}
			
			if (Files.exists(imageDest))
			{
				Image image;
				imageReference = mapping.get(imageKey);
				if (imageReference == null)
					image = saveInGallery(session, post.id(), postImage.id(), postImage.tags(), imageDest);
				else
				{
					image = imageReference.getImage();
					if (session.has(DownloadOption.UPDATE_IMAGES_ALREADY_DOWNLOADED))
					{
						saveInGallery(session, post.id(), postImage.id(), postImage.tags(), image.getAbsolutePath());
					}
				}
				
				downloadsProgressView.newImage(session.id, post.id(), postImage.id(), imageDest);
				downloadsProgressView.endDownload(session.id, post.id(), postImage.id(), null);
				
				return CompletableFuture.completedFuture(image);
			}
		}
		
		try {
			Files.createDirectories(imageDest.getParent());
			
			HttpRequest request = HttpRequest.newBuilder().uri(new URI(postImage.url())).GET().headers(getHeardersForImageDownload(session, postImage)).build();
			return session.sendAsync(request, new MonitorBodyHandler<>(BodyHandlers.ofFile(imageDest), new DownloadListener()
			{
				@Override
				public void onStartDownload(ResponseInfo responseInfo)
				{
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
		try
		{
			// @formatter:off
			final Path fileDest = gallery.toAbsolutePath(makeSafe(
				fileConfiguration.pathPattern.replace("{creatorId}", creatorId)
			                                 .replace("{postId}", post.id())
			                                 .replace("{postDate}", DateTimeFormatter.ISO_LOCAL_DATE.format(post.publishedDatetime()))
			                                 .replace("{postTitle}", post.title())
			                                 .replace("{fileNumber} ", String.format("%02d", fileNumber))
			                                 .replace("{filename}", file.filename())));
			// @formatter:on
			if (Files.exists(fileDest))
				return CompletableFuture.completedFuture(null);
			
			Files.createDirectories(fileDest.getParent());
			
			boolean isZip = isZip(file.filename());
			
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(new URI(file.url()))
			                                 .GET()
			                                 .headers(getHeardersForFileDownload(session, file))
			                                 .build();
			return session.sendAsync(request, BodyHandlers.ofFile(fileDest), new DownloadListener()
			{
				public void onStartDownload(java.net.http.HttpResponse.ResponseInfo responseInfo)
				{
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
			              .thenAccept(response -> unZip(session, post, file, response.body()));
		}
		catch (Exception e)
		{
			return CompletableFuture.failedFuture(e);
		}
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
						saveInGallery(session, post.id, file.id, zipEntry.getName(), file.tags, entryPath);
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
		}
	}
	
	private static final boolean isZip(String filename)
	{
		return filename.toLowerCase(Locale.ROOT).endsWith(".zip");
	}
	
	private static final Path makeSafe(String path)
	{
		int len = path.length();
		StringBuilder safePath = new StringBuilder(len);
		
		int begin = 0;
		while(true)
		{
			// Skip leading spaces
			while (begin < len && path.charAt(begin) == ' ')
				begin++;
			
			int endName = begin;
			char c;
			while (endName < len && (c = path.charAt(endName)) != '/' && c != '\\')
				endName++;
			
			int end = endName;
			while (end > 0 && path.charAt(end - 1) == ' ')
				end--;
			
			if (begin <= end)
				safePath.append(path, begin, end);
			if (endName < len)
			{
				safePath.append(path.charAt(endName));
				begin = endName + 1;
			}
			else
				break;
		}
		
		return Paths.get(safePath.toString());
	}
	
	private static boolean isErrorResponse(HttpResponse<?> response)
	{
		return response.statusCode() >= 400;
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
		else if (session.has(DownloadOption.UPDATE_IMAGES_ALREADY_DOWNLOADED))
		{
			image.getTags().forEach(image::removeTag);
			image.addTag(artist.getTag());
			addTags(image, tags);
		}
		
		ImageReference ref = new ImageReference(image);
		
		ImageKey imagekey = new ImageKey(postId, imageId);
		mapping.put(imagekey, ref);
		
		return image;
	}
	
	private Image saveInGallery(DownloadSession session, String postId, String fileId, String pathInZip, Collection<String> tags, Path path)
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
		Map<Image, List<ImageKey>> imageToKey = mapping.entrySet()
		                                               .stream()
		                                               .filter(e -> e.getValue() != null)
		                                               .collect(Collectors.groupingBy(e -> e.getValue().getImage(),
		                                                                              Collectors.mapping(e -> e.getKey(),
		                                                                                                 Collectors.toList())));
		
		for (Image image : images)
		{
			List<ImageKey> keys = imageToKey.get(image);
			if (keys != null)
				for (ImageKey key : keys)
					mapping.put(key, null);
		}
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
