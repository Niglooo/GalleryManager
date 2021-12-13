package nigloo.gallerymanager.autodownloader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.gallerymanager.model.ImageReference;
import nigloo.tool.StrongReference;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

@JsonAdapter(BaseDownloader.BaseDownloaderAdapter.class)
public abstract class BaseDownloader
{
	private static final Logger LOGGER = LogManager.getLogger(BaseDownloader.class);
	private static final Map<String, Class<? extends BaseDownloader>> TYPE_TO_CLASS = new HashMap<>();
	private static final Map<Class<? extends BaseDownloader>, String> CLASS_TO_TYPE = new HashMap<>();
	
	private static void register(String type, Class<? extends BaseDownloader> clazz)
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
	
	protected static final Executor executor = Executors.newWorkStealingPool();
	
	@JsonAdapter(value = MappingTypeAdapter.class, nullSafe = false)
	private Map<ImageKey, ImageReference> mapping = new LinkedHashMap<>();
	
	protected BaseDownloader()
	{
		Injector.init(this);
	}
	
	protected BaseDownloader(String creatorId)
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
	
	public abstract void download(Properties secrets, boolean checkAllPost) throws Exception;
	
	protected final CompletableFuture<Void> downloadImage(String url,
	                                                      String[] headers,
	                                                      HttpClient httpClient,
	                                                      Semaphore maxConcurrentStreams,
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
				if (!mapping.containsKey(imageKey))
					saveInGallery(postId, imageId, imageDest);
				
				return CompletableFuture.completedFuture(null);
			}
		}
		
		HttpRequest request = null;
		try {
			Files.createDirectories(imageDest.getParent());
			
			request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
			maxConcurrentStreams.acquire();
		}
		catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
		
		return httpClient.sendAsync(request, BodyHandlers.ofFile(imageDest))
		                 .thenApply(saveInGallery(postId, imageId))
		                 .thenApply(print(PrintOption.REQUEST_URL, PrintOption.STATUS_CODE, PrintOption.RESPONSE_BODY))
		                 .thenRun(maxConcurrentStreams::release);
	}
	
	protected final StrongReference<ZonedDateTime> initCurrentMostRecentPost()
	{
		return new StrongReference<>(mostRecentPostCheckedDate);
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
	
	protected final Path makeSafe(Path path)
	{
		Path newPath = path.getRoot();
		
		for (int i = 0 ; i < path.getNameCount() ; i++)
		{
			newPath = newPath.resolve(path.getName(i).toString().trim());
		}
		
		return newPath;
	}
	
	private void saveInGallery(String postId, String imageId, Path path)
	{
		Image image = gallery.getImage(path);
		if (image.isNotSaved())
		{
			image.addTag(artist.getTag());
			gallery.saveImage(image);
		}
		ImageReference ref = new ImageReference(image);
		
		ImageKey imagekey = new ImageKey(postId, imageId);
		mapping.put(imagekey, ref);
	}
	
	private Function<HttpResponse<Path>, HttpResponse<Path>> saveInGallery(String postId, String imageId)
	{
		return response ->
		{
			synchronized (this)
			{
				saveInGallery(postId, imageId, response.body());
				return response;
			}
		};
	}
	
	protected enum PrintOption
	{
		REQUEST_URL, STATUS_CODE, RESPONSE_HEADERS, RESPONSE_BODY
	}
	
	protected static <T> HttpResponse<T> print(HttpResponse<T> response, PrintOption... options)
	{
		synchronized (LOGGER)
		{
			List<PrintOption> optionsLst = (options != null && options.length > 0) ? Arrays.asList(options)
			        : List.of(PrintOption.REQUEST_URL,
			                  PrintOption.STATUS_CODE,
			                  PrintOption.RESPONSE_HEADERS,
			                  PrintOption.RESPONSE_BODY);
			boolean error = response.statusCode() >= 300;
			
			if (optionsLst.contains(PrintOption.REQUEST_URL) || error)
				LOGGER.debug("URL: " + response.request().uri());
			
			if (optionsLst.contains(PrintOption.STATUS_CODE) || error)
				LOGGER.debug("Status: " + response.statusCode());
			
			if (optionsLst.contains(PrintOption.RESPONSE_HEADERS) || error)
			{
				LOGGER.debug("Headers:");
				LOGGER.debug(response.headers()
				                           .map()
				                           .entrySet()
				                           .stream()
				                           .map(e -> "    " + e.getKey() + ": " + e.getValue())
				                           .collect(Collectors.joining("\n")));
			}
			
			if (optionsLst.contains(PrintOption.RESPONSE_BODY) || error)
			{
				LOGGER.debug("Body:");
				LOGGER.debug(prettyToString(response.body()));
			}
		}
		
		return response;
	}
	
	protected static <T> Function<HttpResponse<T>, HttpResponse<T>> print(PrintOption... options)
	{
		return response -> print(response, options);
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
			                        .create()
			                        .toJson(JsonParser.parseString(str));
		}
		catch (Exception e)
		{
			return str;
		}
	}

	protected static <T> Function<HttpResponse<T>, HttpResponse<T>> release(Semaphore semaphore)
	{
		return response -> {
			semaphore.release();
			return response;
		};
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
			return !(obj instanceof ImageKey) ? false
			        : postId.equals(((ImageKey) obj).postId) && imageId.equals(((ImageKey) obj).imageId);
		}
		
		@Override
		public int compareTo(ImageKey o)
		{
			int res = Utils.NATURAL_ORDER.compare(postId, o.postId);
			return (res != 0) ? res : Utils.NATURAL_ORDER.compare(imageId, o.imageId);
		}
	}
	
	public static class BaseDownloaderAdapter
	        implements JsonSerializer<BaseDownloader>, JsonDeserializer<BaseDownloader>
	{
		@Override
		public JsonElement serialize(BaseDownloader src, Type typeOfSrc, JsonSerializationContext context)
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
		public BaseDownloader deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
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
