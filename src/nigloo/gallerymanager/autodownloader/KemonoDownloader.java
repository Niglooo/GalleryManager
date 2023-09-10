package nigloo.gallerymanager.autodownloader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public abstract class KemonoDownloader extends Downloader {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().parseStrict()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter();

        private final String originalProvider;
        private final boolean skipFirstImage;

    protected KemonoDownloader(String originalProvider, boolean skipFirstImage) {
        this.originalProvider = originalProvider;
        this.skipFirstImage = skipFirstImage;
    }

    private String buildUrl(String path) {
        return "https://kemono.party" + path;
    }

    @Override
    protected Iterator<Post> listPosts(DownloadSession session) throws Exception {
        return new KemonoPostIterator(session);
    }

    private class KemonoPostIterator extends BasePostIterator
    {
        private String getPostListUrl;
        private Iterator<Post> postsIt;

        public KemonoPostIterator(DownloadSession session) throws Exception {
            super(session);
            getPostListUrl = buildUrl("/" + originalProvider + "/user/" + creatorId);
            postsIt = Collections.emptyIterator();
            computeNextPost();
        }

        @Override
        protected Post findNextPost() throws Exception {
            if (postsIt.hasNext())
                return postsIt.next();

            if (getPostListUrl == null)
                return null;

            HttpRequest request = HttpRequest.newBuilder().uri(new URI(getPostListUrl)).GET().build();
            HttpResponse<String> response = session.send(request, HttpResponse.BodyHandlers.ofString());

            Document parsedResponse = Jsoup.parseBodyFragment(response.body());

            Element nextLink = parsedResponse.selectFirst("#paginator-top a.next");
            getPostListUrl = nextLink != null ? buildUrl(nextLink.attr("href")) : null;

            postsIt = parsedResponse.select(".card-list__items .post-card").stream().map(postElement -> {
                String postId = postElement.attr("data-id");
                String postUrl = postElement.selectFirst("a").attr("href");
                String postTitle = postElement.selectFirst(".post-card__header").text();
                ZonedDateTime publishedDatetime = DATE_TIME_FORMATTER.parse(postElement.selectFirst(".timestamp").attr("datetime"), LocalDateTime::from).atZone(ZoneOffset.UTC);

                return Post.create(postId, postTitle, publishedDatetime, postUrl);
            }).iterator();

            return findNextPost();
        }
    }

    @Override
    protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post) throws Exception {
        String postUrl = buildUrl((String) post.extraInfo());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(postUrl))
                .GET()
                .build();

        return session.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r ->
        {
            Document htmlPost = Jsoup.parseBodyFragment(r.body());

            // Regular post
            Elements imagesElements = htmlPost.select(".post__thumbnail > a");
            return imagesElements.stream().skip(skipFirstImage ? 1 : 0).map(imageElement ->
            {
                String url = imageElement.attr("href");
                String imageFilename = URLDecoder.decode(imageElement.attr("download"), StandardCharsets.UTF_8);
                String imageId = imageFilename;
                return PostImage.create(imageId, imageFilename, url, null);
            }).toList();
        });
    }

    @Override
    protected CompletableFuture<List<PostFile>> listFiles(DownloadSession session, Post post) throws Exception {
        String postUrl = buildUrl((String) post.extraInfo());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(postUrl))
                .GET()
                .build();

        return session.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r ->
        {
            Document htmlPost = Jsoup.parseBodyFragment(r.body());

            // Regular post
            Elements filesElements = htmlPost.select(".post__attachment > a");
            return filesElements.stream().map(fileElement ->
            {
                String url = fileElement.attr("href");
                String filename = URLDecoder.decode(fileElement.attr("download"), StandardCharsets.UTF_8);
                String fileId = filename;
                return PostFile.create(fileId, filename, url, null);
            }).toList();
        });
    }
}
