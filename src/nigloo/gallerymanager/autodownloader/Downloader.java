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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
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
import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.ImageReference;
import nigloo.tool.StrongReference;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

@JsonAdapter(Downloader.DownloaderAdapter.class)
public abstract class Downloader
{
	private static final Logger LOGGER = LogManager.getLogger(Downloader.class);
	
	protected static final Marker HTTP_REQUEST = MarkerManager.getMarker("HTTP_REQUEST");
	protected static final Marker HTTP_REQUEST_URL = MarkerManager.getMarker("HTTP_REQUEST_URL")
	                                                              .setParents(HTTP_REQUEST);
	protected static final Marker HTTP_REQUEST_HEADERS = MarkerManager.getMarker("HTTP_REQUEST_HEADERS")
	                                                                  .setParents(HTTP_REQUEST);
	
	protected static final Marker HTTP_RESPONSE = MarkerManager.getMarker("HTTP_RESPONSE");
	protected static final Marker HTTP_RESPONSE_URL = MarkerManager.getMarker("HTTP_RESPONSE_URL")
	                                                               .setParents(HTTP_RESPONSE);
	protected static final Marker HTTP_RESPONSE_STATUS = MarkerManager.getMarker("HTTP_RESPONSE_STATUS")
	                                                                  .setParents(HTTP_RESPONSE);
	protected static final Marker HTTP_RESPONSE_HEADERS = MarkerManager.getMarker("HTTP_RESPONSE_HEADERS")
	                                                                   .setParents(HTTP_RESPONSE);
	protected static final Marker HTTP_RESPONSE_BODY = MarkerManager.getMarker("HTTP_RESPONSE_BODY")
	                                                                .setParents(HTTP_RESPONSE);
	
	private static final Map<String, Class<? extends Downloader>> TYPE_TO_CLASS = new HashMap<>();
	private static final Map<Class<? extends Downloader>, String> CLASS_TO_TYPE = new HashMap<>();
	
	private static void register(String type, Class<? extends Downloader> clazz)
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
	protected String imagePathPattern;
	protected ZonedDateTime mostRecentPostCheckedDate;
	
	@JsonAdapter(value = MappingTypeAdapter.class, nullSafe = false)
	private Map<ImageKey, ImageReference> mapping = new LinkedHashMap<>();
	
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
		LOGGER.info("Download for {} from {} with pattern {}",
		            creatorId,
		            CLASS_TO_TYPE.get(getClass()),
		            imagePathPattern);
		DownloadSession session = new DownloadSession();
		doDownload(session, secrets, checkAllPost);
	}
	
	protected abstract void doDownload(DownloadSession session, Properties secrets, boolean checkAllPost)
	        throws Exception;
	
	protected final class DownloadSession
	{
		private final HttpClient httpClient = HttpClient.newBuilder()
		                                                .followRedirects(Redirect.NORMAL)
		                                                .executor(AsyncPools.HTTP_REQUEST)
		                                                .build();
		// TODO init with max_concurrent_streams from http2
		private final Semaphore maxConcurrentStreams = new Semaphore(10);
		private ZonedDateTime currentMostRecentPost = mostRecentPostCheckedDate;
		
		private final List<Image> imagesAdded = new ArrayList<>();
		
		// TODO handle http errors directly here ? Or in saveInGallery and unZip
		public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler)
		        throws IOException,
		        InterruptedException
		{
			logRequest(request);
			HttpResponse<T> response = null;
			maxConcurrentStreams.acquire();
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
		
		public boolean stopCheckingPost(ZonedDateTime publishedDatetime, boolean checkAllPost)
		{
			synchronized (Downloader.this)
			{
				if (currentMostRecentPost == null || currentMostRecentPost.isBefore(publishedDatetime))
					currentMostRecentPost = publishedDatetime;
				
				return mostRecentPostCheckedDate != null && publishedDatetime.compareTo(mostRecentPostCheckedDate) <= 0
				        && !checkAllPost;
			}
		}
		
		protected final void saveLastPublishedDatetime()
		{
			synchronized (Downloader.this)
			{
				if (currentMostRecentPost != null && (mostRecentPostCheckedDate == null
				        || mostRecentPostCheckedDate.isBefore(currentMostRecentPost)))
					mostRecentPostCheckedDate = currentMostRecentPost;
			}
		}
	}
	
	protected final CompletableFuture<Image> downloadImage(DownloadSession session,
	                                                      String url,
	                                                      String[] headers,
	                                                      String postId,
	                                                      String imageId,
	                                                      ZonedDateTime publishedDatetime,
	                                                      String postTitle,
	                                                      int imageNumber,
	                                                      String imageFilename)
	{
		ImageKey imageKey = new ImageKey(postId, imageId);
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
				imageDest = Paths.get(imagePathPattern.replace("{creatorId}", creatorId.trim())
				                                      .replace("{postId}", postId.trim())
				                                      .replace("{postDate}",
				                                               DateTimeFormatter.ISO_LOCAL_DATE.format(publishedDatetime))
				                                      .replace("{postTitle}", postTitle.trim())
				                                      .replace("{imageNumber}", String.format("%02d", imageNumber))
				                                      .replace("{imageFilename}", imageFilename.trim()));
				imageDest = makeSafe(gallery.toAbsolutePath(imageDest));
				imageReference = null;
			}
			
			if (Files.exists(imageDest))
			{
				Image image;
				imageReference = mapping.get(imageKey);
				if (imageReference == null)
					image = saveInGallery(session, postId, imageId, imageDest);
				else
					image = imageReference.getImage();
				
				return CompletableFuture.completedFuture(image);
			}
		}
		
		try {
			Files.createDirectories(imageDest.getParent());
			
			HttpRequest request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
			return session.sendAsync(request, BodyHandlers.ofFile(imageDest))
			              .thenApply(saveInGallery(session, postId, imageId));
		}
		catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	protected final Function<HttpResponse<Path>, HttpResponse<Path>> unZip(boolean isZip, boolean autoExtractZip)
	{
		return response ->
		{
			if (!isZip || !autoExtractZip)
				return response;
			
			Path filePath = response.body();
			Path zipFilePath = filePath.resolveSibling(filePath.getFileName() + ".zip");
			
			LOGGER.debug("Unziping: " + zipFilePath);
			
			try
			{
				int nbAttempt = 0;
				while (true)
				{
					try
					{
						Files.move(filePath, zipFilePath);
						break;
					}
					catch (Exception e)
					{
						if (nbAttempt++ >= 10)
							throw e;
						
						try
						{
							Thread.sleep(200);
						}
						catch (InterruptedException e1)
						{
							Thread.currentThread().interrupt();
						}
					}
				}
				Files.createDirectory(filePath);
			}
			catch (IOException e)
			{
				LOGGER.error("Cannot rename " + filePath + " to " + zipFilePath, e);
				return response;
			}
			
			try
			{
				ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFilePath));
				ZipEntry zipEntry = zis.getNextEntry();
				while (zipEntry != null)
				{
					Path entryPath = filePath.resolve(zipEntry.getName());
					
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
				
				Files.delete(zipFilePath);
			}
			catch (IOException e)
			{
				LOGGER.error("Error when unzipping " + zipFilePath, e);
				return response;
			}
			
			return response;
		};
	}
	
	protected final void updateCurrentMostRecentPost(StrongReference<ZonedDateTime> currentMostRecentPost,
	                                                 ZonedDateTime publishedDatetime)
	{
		if (currentMostRecentPost.get() == null || currentMostRecentPost.get().isBefore(publishedDatetime))
			currentMostRecentPost.set(publishedDatetime);
	}
	
	protected final boolean dontCheckPost(ZonedDateTime publishedDatetime, boolean checkAllPost)
	{
		return mostRecentPostCheckedDate != null && publishedDatetime.compareTo(mostRecentPostCheckedDate) <= 0
		        && !checkAllPost;
	}
	
	protected final void saveCurrentMostRecentPost(StrongReference<ZonedDateTime> currentMostRecentPost)
	{
		if (currentMostRecentPost.get() != null && (mostRecentPostCheckedDate == null
		        || mostRecentPostCheckedDate.isBefore(currentMostRecentPost.get())))
			mostRecentPostCheckedDate = currentMostRecentPost.get();
	}
	
	protected static final Path makeSafe(Path path)
	{
		Path newPath = path.getRoot();
		
		for (int i = 0 ; i < path.getNameCount() ; i++)
		{
			newPath = newPath.resolve(path.getName(i).toString().trim());
		}
		
		return newPath;
	}
	
	protected static boolean isErrorResponse(HttpResponse<?> response)
	{
		return response.statusCode() >= 300;
	}
	
	private synchronized Image saveInGallery(DownloadSession session, String postId, String imageId, Path path)
	{
		Image image = gallery.getImage(path);
		if (image.isNotSaved())
		{
			image.addTag(artist.getTag());
			gallery.saveImage(image);
			session.imagesAdded.add(image);
		}
		ImageReference ref = new ImageReference(image);
		
		ImageKey imagekey = new ImageKey(postId, imageId);
		mapping.put(imagekey, ref);
		
		return image;
	}
	
	private Function<HttpResponse<Path>, Image> saveInGallery(DownloadSession session, String postId, String imageId)
	{
		return response -> saveInGallery(session, postId, imageId, response.body());
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
	
	public record ImageKey(String postId, String imageId) implements Comparable<ImageKey>
	{
		public ImageKey
		{
			Objects.requireNonNull(postId, "postId");
			Objects.requireNonNull(imageId, "imageId");
		}
		
		@Override
		public int hashCode()
		{
			return postId.hashCode() ^ imageId.hashCode();
		}
		
		@Override
		public boolean equals(Object obj)
		{
			return (obj instanceof ImageKey other) ? postId.equals(other.postId) && imageId.equals(other.imageId)
			        : false;
		}
		
		@Override
		public int compareTo(ImageKey o)
		{
			int res = Utils.NATURAL_ORDER.compare(postId, o.postId);
			return (res != 0) ? res : Utils.NATURAL_ORDER.compare(imageId, o.imageId);
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
