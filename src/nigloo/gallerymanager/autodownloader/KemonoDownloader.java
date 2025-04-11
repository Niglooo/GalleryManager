package nigloo.gallerymanager.autodownloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import nigloo.tool.Utils;
import nigloo.tool.gson.JsonHelper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


public abstract class KemonoDownloader extends Downloader {
    // https://kemono.su/documentation/api
    private static final int PAGE_SIZE = 50; // Forced by the API
    private static final String POSTS_DETAIL_CACHE_KEY = "posts-detail";

    private final transient String originalProvider;

    protected KemonoDownloader(String originalProvider) {
        this.originalProvider = originalProvider;
    }

    @Override
    protected void onStartDownload(DownloadSession session) throws Exception
    {
        session.setExtaInfo(POSTS_DETAIL_CACHE_KEY, new ConcurrentHashMap<>());
    }

    private String buildApiUrl(String path) {
        return "https://kemono.su/api/v1" + path;
    }

    private String buildDataUrl(String server, String path) {
        return server + "/data" + path;
    }

    @Override
    protected Iterator<Post> listPosts(DownloadSession session) throws Exception {
        return new KemonoPostIterator(session);
    }

    private class KemonoPostIterator extends BasePostIterator
    {
        private int nextPage = 0;
        private Iterator<Post> postsIt;

        public KemonoPostIterator(DownloadSession session) throws Exception {
            super(session);
            postsIt = Collections.emptyIterator();
            computeNextPost();
        }

        @Override
        protected Post findNextPost() throws Exception {
            if (postsIt.hasNext())
                return postsIt.next();

            if (nextPage == -1)
                return null;

            HttpRequest request = HttpRequest.newBuilder().uri(new URI(buildApiUrl("/" + originalProvider + "/user/" + creatorId+"?o="+(nextPage * PAGE_SIZE)))).GET().build();
            HttpResponse<JsonElement> response = session.send(request, JsonHelper.httpBodyHandler());

            if (response.body().getAsJsonArray().isEmpty()) {
                nextPage = -1;
                return null;
            }
            else {
                nextPage++;
            }

            postsIt = JsonHelper.stream(response.body().getAsJsonArray()).map(jPost -> {
                String postId = JsonHelper.followPath(jPost, "id");
                String postTitle = JsonHelper.followPath(jPost, "title");
                ZonedDateTime publishedDatetime = LocalDateTime.parse(JsonHelper.followPath(jPost, "published")).atZone(ZoneOffset.UTC);

                return Post.create(postId, postTitle, publishedDatetime, null);
            }).iterator();

            return findNextPost();
        }
    }

    private JsonObject getPostDetail(DownloadSession session, Post post)
    {
        Map<String, JsonObject> cache = session.getExtraInfo(POSTS_DETAIL_CACHE_KEY);
        return cache.computeIfAbsent(post.id(), postId -> {
            try
            {
                HttpRequest request = HttpRequest.newBuilder().uri(new URI(buildApiUrl("/" + originalProvider + "/user/" + creatorId + "/post/" + postId))).GET().build();
                return session.send(request, JsonHelper.httpBodyHandler()).body().getAsJsonObject();
            }
            catch (URISyntaxException | IOException | InterruptedException e) {
                throw Utils.asRunTimeException(e);
            }
        });
    }

    @Override
    protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post) {
        JsonObject jPost = getPostDetail(session, post);
        JsonArray jImages = JsonHelper.followPath(jPost, "previews", JsonArray.class);

        List<PostImage> images = JsonHelper.stream(jImages)
                                           .filter(jImage -> !"embed".equals(JsonHelper.followPath(jImage, "type")))
                                           .map( jImage -> {
            String path = JsonHelper.followPath(jImage, "path");
            String server = JsonHelper.followPath(jImage, "server");
            String url = buildDataUrl(server, path);
            String imageFilename = JsonHelper.followPath(jImage, "name");
            String imageId = imageFilename;
            return PostImage.create(imageId, imageFilename, url, null);
        }).collect(Collectors.toCollection(ArrayList::new));

        if (!images.isEmpty() && images.stream().skip(1).anyMatch(images.getFirst()::equals)) {
            images.removeFirst();
        }

        return CompletableFuture.completedFuture(images);
    }

    @Override
    protected CompletableFuture<List<PostFile>> listFiles(DownloadSession session, Post post) {
        JsonObject jPost = getPostDetail(session, post);
        JsonArray jFiles = JsonHelper.followPath(jPost, "attachments", JsonArray.class);

        List<PostFile> files = JsonHelper.stream(jFiles).map( jImage -> {
            String path = JsonHelper.followPath(jImage, "path");
            String server = JsonHelper.followPath(jImage, "server");
            String url = buildDataUrl(server, path);
            String filename = JsonHelper.followPath(jImage, "name");
            String fileId = filename;
            return PostFile.create(fileId, filename, url, null);
        }).toList();

        return CompletableFuture.completedFuture(files);
    }
}
