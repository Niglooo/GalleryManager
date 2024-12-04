package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import nigloo.tool.gson.JsonHelper;
import org.jsoup.Jsoup;

public class PatreonDownloader extends Downloader
{
	private static final String HEADERS_KEY = "headers";
	
	private record RessourcesId(String id, String type) {}
	private record PostExtraInfo(JsonElement jPost, Map<RessourcesId, JsonElement> resources) {}

	@Override
	public DownloaderType getType()
	{
		return DownloaderType.PATREON;
	}

	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		session.setExtaInfo(HEADERS_KEY, getHeaders(session));
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new PatreonPostIterator(session);
	}
	
	private class PatreonPostIterator extends BasePostIterator
	{
		private final String campaignId;
		
		private String nextPageUrl;
		private Iterator<JsonElement> postsIt;
		private Map<RessourcesId, JsonElement> currentResourcesIncluded;
		
		public PatreonPostIterator(DownloadSession session) throws Exception
		{
			super(session);
			
			HttpRequest request = HttpRequest.newBuilder()
			                    .uri(new URI("https://www.patreon.com/c/" + creatorId + "/posts"))
			                    .GET()
								.headers(session.getExtraInfo(HEADERS_KEY))
			                    .version(Version.HTTP_1_1)// Avoid cloudflare bullshit
			                    .build();
			String homePageHtml = session.send(request, BodyHandlers.ofString()).body();
			JsonElement bootstrap = JsonParser.parseString(Jsoup.parse(homePageHtml).body().getElementById("__NEXT_DATA__").data());

			JsonElement currentUser = JsonHelper.followPath(bootstrap, "props.pageProps.bootstrapEnvelope.commonBootstrap.currentUser", JsonElement.class);
			if (currentUser == null || currentUser.isJsonNull()) {
				throw new DownloaderSessionExpiredException();
			}
			
			this.campaignId = JsonHelper.followPath(bootstrap, "props.pageProps.bootstrapEnvelope.pageBootstrap.campaign.data.id");
			this.nextPageUrl = "https://www.patreon.com/api/campaigns/"+campaignId+"/posts" +
					"?include=campaign%2Caccess_rules%2Caccess_rules.tier.null%2Cattachments_media%2Caudio%2Caudio_preview.null%2Cdrop%2Cimages%2Cmedia%2Cnative_video_insights%2Cpoll.choices%2Cpoll.current_user_responses.user%2Cpoll.current_user_responses.choice%2Cpoll.current_user_responses.poll%2Cuser%2Cuser_defined_tags%2Cti_checks%2Cvideo.null%2Ccontent_unlock_options.product_variant.null" +
					"&fields[campaign]=currency%2Cshow_audio_post_download_links%2Cavatar_photo_url%2Cavatar_photo_image_urls%2Cearnings_visibility%2Cis_nsfw%2Cis_monthly%2Cname%2Curl" +
					"&fields[post]=change_visibility_at%2Ccomment_count%2Ccommenter_count%2Ccontent%2Ccreated_at%2Ccurrent_user_can_comment%2Ccurrent_user_can_delete%2Ccurrent_user_can_report%2Ccurrent_user_can_view%2Ccurrent_user_comment_disallowed_reason%2Ccurrent_user_has_liked%2Cembed%2Cimage%2Cinsights_last_updated_at%2Cis_paid%2Clike_count%2Cmeta_image_url%2Cmin_cents_pledged_to_view%2Cmonetization_ineligibility_reason%2Cpost_file%2Cpost_metadata%2Cpublished_at%2Cpatreon_url%2Cpost_type%2Cpledge_url%2Cpreview_asset_type%2Cthumbnail%2Cthumbnail_url%2Cteaser_text%2Ctitle%2Cupgrade_url%2Curl%2Cwas_posted_by_campaign_owner%2Chas_ti_violation%2Cmoderation_status%2Cpost_level_suspension_removal_date%2Cpls_one_liners_by_category%2Cvideo%2Cvideo_preview%2Cview_count%2Ccontent_unlock_options%2Cis_new_to_current_user%2Cwatch_state" +
					"&fields[post_tag]=tag_type%2Cvalue" +
					"&fields[user]=image_url%2Cfull_name%2Curl" +
					"&fields[access_rule]=access_rule_type%2Camount_cents" +
					"&fields[media]=id%2Cimage_urls%2Cdisplay%2Cdownload_url%2Cmetadata%2Cfile_name" +
					"&fields[native_video_insights]=average_view_duration%2Caverage_view_pct%2Chas_preview%2Cid%2Clast_updated_at%2Cnum_views%2Cpreview_views%2Cvideo_duration" +
					"&fields[content-unlock-option]=content_unlock_type" +
					"&fields[product-variant]=price_cents%2Ccurrency_code%2Ccheckout_url%2Cis_hidden%2Cpublished_at_datetime%2Ccontent_type%2Corders_count%2Caccess_metadata" +
					"&filter[campaign_id]=" + campaignId +
					"&filter[contains_exclusive_posts]=true" +
					"&filter[is_draft]=false" +
					"&page[cursor]=null" +
					"&page[count]=6" +
					"&filter[is_by_creator]=true" +
					"&sort=-published_at" +
					"&json-api-use-default-includes=false" +
					"&json-api-version=1.0";
			this.postsIt = Collections.emptyIterator();
			this.currentResourcesIncluded = null;

			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (postsIt.hasNext())
			{
				JsonElement jPost = postsIt.next();
				
				boolean canView = JsonHelper.followPath(jPost, "attributes.current_user_can_view", boolean.class);
				if (!canView)
					return findNextPost();
				
				String id = JsonHelper.followPath(jPost, "id");
				String title = Objects.toString(JsonHelper.followPath(jPost, "attributes.title"), "");
				ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(jPost, "attributes.published_at"));
				
				return Post.create(id, title, publishedDatetime, new PostExtraInfo(jPost, currentResourcesIncluded));
			}
			else if (nextPageUrl != null)
			{
				HttpRequest request = HttpRequest.newBuilder()
				                    .uri(new URI(nextPageUrl))
				                    .GET()
				                    .headers(session.getExtraInfo(HEADERS_KEY))
				                    .build();
				JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
				
				nextPageUrl = Optional.ofNullable(JsonHelper.followPath(response, "links.next")).map(link -> "https://" + link).orElse(null);
				postsIt = JsonHelper.followPath(response, "data", JsonArray.class).iterator();
				
				currentResourcesIncluded = new HashMap<>();
				JsonHelper.followPath(response, "included", JsonArray.class).forEach(item ->
				{
					String id = JsonHelper.followPath(item, "id");
					String type = JsonHelper.followPath(item, "type");
					currentResourcesIncluded.put(new RessourcesId(id, type), item);
				});
				
				return findNextPost();
			}
			else
			{
				return null;
			}
		}
	}
	
	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post)
	{
		List<JsonElement> jImages = getRelationshipElements(post, "images");
		List<PostImage> images;
		if (jImages.isEmpty())
		{
			images = List.of();
		}
		else
		{
			images = new ArrayList<>(jImages.size());
			Collection<String> tags = getPostTags(post);
			
			for (JsonElement jImage : jImages)
			{
				String id = JsonHelper.followPath(jImage, "id");
				String filename = JsonHelper.followPath(jImage, "attributes.file_name");
				String url = JsonHelper.followPath(jImage, "attributes.image_urls.original");
				
				images.add(PostImage.create(id, filename, url, tags));
			}
			
			List<String> idsOrder = JsonHelper.stream(JsonHelper.followPath(((PostExtraInfo) post.extraInfo()).jPost(),
			                                                                "attributes.post_metadata.image_order",
			                                                                JsonArray.class))
			                                  .map(JsonElement::getAsString)
			                                  .toList();
			images.sort(Comparator.comparing(PostImage::id, Comparator.comparingInt(idsOrder::indexOf)));
		}
		
		return CompletableFuture.completedFuture(images);
	}
	
	@Override
	protected CompletableFuture<List<PostFile>> listFiles(DownloadSession session, Post post) throws Exception
	{
		List<JsonElement> jFiles = getRelationshipElements(post, "attachments_media");
		List<PostFile> files;
		if (jFiles.isEmpty())
		{
			files = List.of();
		}
		else
		{
			files = new ArrayList<>(jFiles.size());
			Collection<String> tags = getPostTags(post);
			
			for (JsonElement jFile : jFiles)
			{
				String id = JsonHelper.followPath(jFile, "id");
				String filename = JsonHelper.followPath(jFile, "attributes.file_name");
				String url = JsonHelper.followPath(jFile, "attributes.download_url");
				
				files.add(PostFile.create(id, filename, url, tags));
			}
		}
		
		return CompletableFuture.completedFuture(files);
	}

	@Override
	public boolean supportLikePost()
	{
		return true;
	}

	@Override
	protected CompletableFuture<Boolean> likePost(DownloadSession session, Post post) throws Exception
	{
		PostExtraInfo extraInfo = (PostExtraInfo) post.extraInfo();
		JsonElement jPost = extraInfo.jPost();
		boolean isLiked = JsonHelper.followPath(jPost, "attributes.current_user_has_liked", boolean.class);

		// Post already liked
		if (isLiked)
		{
			return CompletableFuture.completedFuture(null);
		}

		HttpRequest request = HttpRequest.newBuilder()
										 .uri(new URI("https://www.patreon.com/api/posts/"+post.id()+"/likes?json-api-version=1.0&json-api-use-default-includes=false&include=[]"))
										 .POST(BodyPublishers.ofString("{}"))
										 .header("Content-Type", "application/vnd.api+json")
										 .headers(session.getExtraInfo(HEADERS_KEY))
										 .header("X-Csrf-Signature", session.getSecret("patreon.x-csrf-signature"))
										 .build();

		return session.sendAsync(request, JsonHelper.httpBodyHandler())
					  .thenApply(r -> JsonHelper.followPath(r.body(), "data.id") != null);
	}

	private Collection<String> getPostTags(Post post)
	{
		return getRelationshipElements(post, "user_defined_tags")
				.stream()
				.map(jTag -> JsonHelper.followPath(jTag, "attributes.value"))
				.toList();
	}
	
	private List<JsonElement> getRelationshipElements(Post post, String relationship)
	{
		PostExtraInfo extraInfo = (PostExtraInfo) post.extraInfo();
		JsonElement jPost = extraInfo.jPost();
		Map<RessourcesId, JsonElement> resources = extraInfo.resources();
		
		JsonArray jElementsRef = JsonHelper.followPath(jPost,
		                                               "relationships." + relationship + ".data",
		                                               JsonArray.class);
		
		ArrayList<JsonElement> elements = new ArrayList<>();
		if (jElementsRef != null)
		{
			elements.ensureCapacity(jElementsRef.size());
			
			for (JsonElement jElementRef : jElementsRef)
			{
				String id = JsonHelper.followPath(jElementRef, "id");
				String type = JsonHelper.followPath(jElementRef, "type");
				JsonElement jElement = resources.get(new RessourcesId(id, type));
				
				
				
				elements.add(jElement);
			}
		}
		
		return elements;
	}

	@Override
	protected String[] getHeadersForImageDownload(DownloadSession session, PostImage image)
	{
		return session.getExtraInfo(HEADERS_KEY);
	}
	
	@Override
	protected String[] getHeadersForFileDownload(DownloadSession session, PostFile image)
	{
		return session.getExtraInfo(HEADERS_KEY);
	}
	
	private String[] getHeaders(DownloadSession session)
	{
		// @formatter:off
		return new String[] {
			"accept", "*/*",
			"accept-encoding", "gzip, deflate",
			"accept-language", "fr-FR,fr;q=0.8",
			"baggage", session.getSecret("patreon.baggage"),
			"content-type", "application/vnd.api+json",
			"cookie", session.getSecret("patreon.cookie"),
			"priority", "u=1, i",
			"referer", "https://www.patreon.com/c/"+creatorId+"/posts",
			"sec-ch-ua", "\"Brave\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
			"sec-ch-ua-mobile", "?0",
			"sec-ch-ua-platform", "\"Windows\"",
			"sec-fetch-dest", "empty",
			"sec-fetch-mode", "cors",
			"sec-fetch-site", "same-origin",
			"sec-gpc", "1",
			"sentry-trace", session.getSecret("patreon.sentry-trace"),
			"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
		};
		// @formatter:on
	}
}
