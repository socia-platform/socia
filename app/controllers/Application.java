package controllers;

import com.typesafe.config.ConfigFactory;
import controllers.Navigation.Level;
import models.*;
import models.services.ElasticsearchResponse;
import models.services.ElasticsearchService;
import org.apache.commons.lang3.StringEscapeUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import play.Logger;
import play.Routes;
import play.api.libs.json.Json;
import play.data.Form;
import play.db.jpa.Transactional;
import play.mvc.BodyParser;
import play.mvc.Result;
import play.mvc.Security;
import play.utils.UriEncoding;
import views.html.*;
import views.html.snippets.streamRaw;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;


@Transactional
public class Application extends BaseController {
	
	static Form<Post> postForm = Form.form(Post.class);
	static final int LIMIT = ConfigFactory.load().getInt("socia.post.limit");
	static final int PAGE = 1;

	
	public static Result javascriptRoutes() {
		response().setContentType("text/javascript");

		return ok(
                Routes.javascriptRouter("jsRoutes",
				    controllers.routes.javascript.GroupController.create(),
				    controllers.routes.javascript.GroupController.update()
				)
        );
	}

	@Security.Authenticated(Secured.class)
	public static Result index() {
		Navigation.set(Level.STREAM, "Alles");
		Account currentAccount = Component.currentAccount();
		return ok(stream.render(currentAccount, Post.getStream(currentAccount, LIMIT, PAGE), postForm, Post.countStream(currentAccount, ""), LIMIT, PAGE, "all"));
	}
	
	public static Result help() {
		Navigation.set(Level.HELP);
		return ok(help.render());
	}
	
	@Security.Authenticated(Secured.class)
	public static Result stream(String filter, int page, boolean raw) {
        switch (filter) {
            case "account":
                Navigation.set(Level.STREAM, "Eigene Posts");
                break;
            case "group":
                Navigation.set(Level.STREAM, "Gruppen");
                break;
            case "contact":
                Navigation.set(Level.STREAM, "Kontakte");
                break;
            case "bookmark":
                Navigation.set(Level.STREAM, "Favoriten");
                break;
            default:
                Navigation.set(Level.STREAM, "Alles");
        }
		Account currentAccount = Component.currentAccount();

        if(raw) {
            return ok(streamRaw.render(Post.getFilteredStream(currentAccount, LIMIT, page, filter), postForm, Post.countStream(currentAccount, filter), LIMIT, page, filter));
        } else {
            return ok(stream.render(currentAccount, Post.getFilteredStream(currentAccount, LIMIT, page, filter), postForm, Post.countStream(currentAccount, filter), LIMIT, page, filter));
        }
	}

    public static Result searchSuggestions(String query) throws ExecutionException, InterruptedException {
        SearchResponse response = ElasticsearchService.doSearch("searchSuggestions", query, "all", null, 1,  Component.currentAccount().id.toString(), asList("name","title"), asList("user.friends", "group.member"));
        return ok(response.toString());
    }

    public static Result searchHome() {
        Navigation.set(Level.SEARCH);
        return ok(views.html.Search.search.render());
    }
	
	@Security.Authenticated(Secured.class)
	public static Result search(int page) throws ExecutionException, InterruptedException {
        Account currentAccount = Component.currentAccount();
        String keyword = Form.form().bindFromRequest().field("keyword").value();
        String mode = Form.form().bindFromRequest().field("mode").value();
        String studycourseParam = Form.form().bindFromRequest().field("studycourse").value();
        String degreeParam = Form.form().bindFromRequest().field("degree").value();
        String semesterParam = Form.form().bindFromRequest().field("semester").value();
        String roleParam = Form.form().bindFromRequest().field("role").value();
        String grouptypeParam = Form.form().bindFromRequest().field("grouptype").value();

        Navigation.set(Level.SEARCH, "\""+keyword+"\"");

        HashMap<String, String[]> facets = new HashMap<>();
        facets.put("studycourse", buildUserFacetList(studycourseParam));
        facets.put("degree", buildUserFacetList(degreeParam));
        facets.put("semester", buildUserFacetList(semesterParam));
        facets.put("role", buildUserFacetList(roleParam));
        facets.put("grouptype", buildUserFacetList(grouptypeParam));


        if (keyword == null) {
            flash("info","Nach was suchst du?");
            return redirect(controllers.routes.Application.searchHome());
        }

        if (mode == null) mode = "all";

        Pattern pt = Pattern.compile("[^ a-zA-Z0-9\u00C0-\u00FF]");
        Matcher match= pt.matcher(keyword);
        while(match.find())
        {
            String s = match.group();
            keyword=keyword.replaceAll("\\"+s, "");
            flash("info","Dein Suchwort enthielt ungültige Zeichen, die für die Suche entfernt wurden!");
        }

        SearchResponse response;
        ElasticsearchResponse searchResponse;

        try {
            response = ElasticsearchService.doSearch("search", keyword.toLowerCase(), mode, facets, page, currentAccount.id.toString(), asList("name", "title", "content"), asList("user.friends", "user.owner", "group.member", "group.owner", "post.owner", "post.viewable"));
            searchResponse = new ElasticsearchResponse(response, keyword, mode);
        } catch (NoNodeAvailableException nna) {
            flash("error", "Leider steht die Suche zur Zeit nicht zur Verfügung!");
            return ok(views.html.Search.search.render());
        }


        return ok(views.html.Search.searchresult.render(
                page,
                LIMIT,
                searchResponse));
	}

    /**
     * searchResponse.resultList,
     searchResponse.response.getTookInMillis(),
     searchResponse.getDocumentCount(),
     searchResponse.lUserDocuments,
     searchResponse.lGroupDocuments,
     searchResponse.lPostDocuments
     * @return
     */

	public static Result error() {
		Navigation.set("404");
		return ok(error.render());
	}
	
	public static Result feedback() {
        Navigation.set("Feedback");
        return ok(feedback.render(postForm));
    }
	
	public static Result addFeedback() {
		
		Account account = Component.currentAccount();
		Group group = Group.findByTitle("Socia Feedback");
		
		// Guest case
		if(account == null) {
			account = Account.findByEmail(play.Play.application().configuration().getString("socia.admin.mail"));
		}
		
		Form<Post> filledForm = postForm.bindFromRequest();
		if (filledForm.hasErrors()) {
			flash("error", "Jo, fast. Probiere es noch einmal mit Inhalt ;-)");
			return redirect(controllers.routes.Application.feedback());
		} else {
			Post p = filledForm.get();
			p.owner = account;
            p.group = group;
            p.create();
            flash("success","Vielen Dank für Dein Feedback!");
		}

		return redirect(controllers.routes.Application.index());
	}

    public static Result imprint() {
        Navigation.set("Impressum");
        return ok(imprint.render());
    }

    public static Result privacy() {
        Navigation.set("Datenschutzerklärung");
        return ok(privacy.render());
    }

    public static Result defaultRoute(String path) {
		Logger.info(path+" nicht gefunden");
		return redirect(controllers.routes.Application.index());
	}

    private static String[] buildUserFacetList(String parameter) {
        if (parameter != null) {
            return parameter.split(",");
        }
        return new String[0];
    }

}
