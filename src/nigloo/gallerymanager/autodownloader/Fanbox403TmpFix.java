package nigloo.gallerymanager.autodownloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import nigloo.gallerymanager.autodownloader.Downloader.Post;
import nigloo.gallerymanager.model.Artist;
import org.openqa.selenium.json.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

class Fanbox403TmpFix
{
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path cacheFile;
    private final Object lock;
    private final Gson gson;

    private static final Type CACHE_TYPE = new TypeToken<TreeMap<String, DownloaderCache>>(){}.getType();

    public Fanbox403TmpFix(Path cacheFile) {
        this.cacheFile = cacheFile;
        this.lock = LOCKS.computeIfAbsent(cacheFile, c -> new Object());
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .serializeNulls()
                .create();
    }

    public JsonObject getPostDetail(FanboxDownloader fanboxDownloader, Post post, IOException error) throws IOException
    {
        synchronized (lock) {
            try
            {
                TreeMap<String, DownloaderCache> cache;
                try (InputStream is = Files.newInputStream(cacheFile))
                {
                    cache = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), CACHE_TYPE);
                }

                String creatorId = fanboxDownloader.getCreatorId();
                DownloaderCache dCache = cache.computeIfAbsent(creatorId, i -> new DownloaderCache(fanboxDownloader.getArtist()));

                JsonElement postDetail = dCache.posts.computeIfAbsent(post.id(), i -> new PostCache(fanboxDownloader, post)).postDetail;

                try (OutputStream os = Files.newOutputStream(cacheFile);
                     OutputStreamWriter out = new OutputStreamWriter(os, StandardCharsets.UTF_8))
                {
                    gson.toJson(cache, out);
                }

                if (postDetail instanceof JsonObject object) {
                    return object;
                }

                throw error;
            }
            catch (Exception e) {
                if (error != e) {
                    error.addSuppressed(e);
                }
                throw error;
            }
        }
    }

    private static class DownloaderCache
    {
        String artistName;
        TreeMap<String, PostCache> posts;

        DownloaderCache(Artist artist) {
            this.artistName = artist.getName();
            this.posts = new TreeMap<>();
        }
    }

    private static class PostCache
    {
        String title;
        String publishedDatetime;
        String url;
        JsonElement postDetail;

        PostCache(FanboxDownloader downloader, Post post) {
            this.title = post.title();
            this.publishedDatetime = post.publishedDatetime().toString();
            this.url = "https://www.fanbox.cc/@" + downloader.getCreatorId() + "/posts/" + post.id();
            this.postDetail = new JsonPrimitive("403 FIXME");
        }
    }
}
