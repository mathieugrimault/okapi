package org.folio.okapi.managers;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.Json;
import io.vertx.core.net.ProxyOptions;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.bean.ModuleDescriptor;
import org.folio.okapi.bean.PullDescriptor;
import org.folio.okapi.common.ErrorType;
import org.folio.okapi.common.ExtendedAsyncResult;
import org.folio.okapi.common.Failure;
import org.folio.okapi.common.Messages;
import org.folio.okapi.common.OkapiLogger;
import org.folio.okapi.common.Success;

@java.lang.SuppressWarnings({"squid:S1192"})
public class PullManager {

  private final Logger logger = OkapiLogger.get();
  private final WebClient webClient;
  private final ModuleManager moduleManager;
  private final Messages messages = Messages.getInstance();

  /**
   * Construct Pull Manager.
   * @param vertx Vertx
   * @param moduleManager Module Manager
   */
  public PullManager(Vertx vertx, ModuleManager moduleManager) {
    this.webClient = WebClient.create(vertx,
      new WebClientOptions(
        new HttpClientOptions().setProxyOptions(
          new ProxyOptions().setHost(System.getProperty("proxyHost"))
        )
      )
    );
    this.moduleManager = moduleManager;
  }

  private void getRemoteUrl(Iterator<String> it,
                            Handler<ExtendedAsyncResult<List<String>>> fut) {
    if (!it.hasNext()) {
      fut.handle(new Failure<>(ErrorType.NOT_FOUND, messages.getMessage("11000")));
      return;
    }
    final String baseUrl = it.next();
    String url = baseUrl;
    if (!url.endsWith("/")) {
      url += "/";
    }
    url += "_/version";
    final Buffer body = Buffer.buffer();
    webClient.getAbs(url)
        .expect(ResponsePredicate.status(200, 202))
        .send(res1 -> {
          if (res1.succeeded()) {
            HttpResponse<io.vertx.core.buffer.Buffer> res = res1.result();
            List<String> result = new LinkedList<>();
            result.add(baseUrl);
            result.add(body.toString());
            fut.handle(new Success<>(result));
          } else {
            logger.warn("pull for {} failed: {}", baseUrl,
                res1.cause().getMessage(), res1.cause());
            getRemoteUrl(it, fut);
            // fut.handle(new Failure<>(ErrorType.INTERNAL, res1.cause().getMessage())); ???
            return;
          }
        });
  }

  private void getList(String urlBase,
                       Collection<ModuleDescriptor> skipList,
                       Handler<ExtendedAsyncResult<ModuleDescriptor[]>> fut) {
    String url = urlBase;
    if (!url.endsWith("/")) {
      url += "/";
    }
    url += "_/proxy/modules";
    if (skipList != null) {
      url += "?full=true";
    }

    webClient.getAbs(url)
        .expect(ResponsePredicate.status(200, 202))
        .expect(ResponsePredicate.JSON)
        .send(res1 -> {
          if (res1.succeeded()) {
            HttpResponse<io.vertx.core.buffer.Buffer> res  = res1.result();
            ModuleDescriptor[] ml = res.bodyAsJson(ModuleDescriptor[].class);
            fut.handle(new Success<>(ml));
          } else {
            fut.handle(new Failure<>(ErrorType.INTERNAL, res1.cause().getMessage()));
            return;
          }
        });

    if (skipList != null) {
      String[] idList = new String[skipList.size()];
      int i = 0;
      for (ModuleDescriptor md : skipList) {
        idList[i] = md.getId();
        i++;
      }
      Json.encodePrettily(idList);
    }
  }

  private void pullSmart(String remoteUrl, Collection<ModuleDescriptor> localList,
                         Handler<ExtendedAsyncResult<List<ModuleDescriptor>>> fut) {

    getList(remoteUrl, localList, resRemote -> {
      if (resRemote.failed()) {
        fut.handle(new Failure<>(resRemote.getType(), resRemote.cause()));
        return;
      }
      ModuleDescriptor[] remoteList = resRemote.result();
      List<ModuleDescriptor> mustAddList = new LinkedList<>();
      List<ModuleDescriptor> briefList = new LinkedList<>();
      Set<String> enabled = new TreeSet<>();
      for (ModuleDescriptor md : localList) {
        enabled.add(md.getId());
      }
      for (ModuleDescriptor md : remoteList) {
        if (!"okapi".equals(md.getProduct()) && !enabled.contains(md.getId())) {
          mustAddList.add(md);
          briefList.add(new ModuleDescriptor(md, true));
        }
      }
      logger.info("pull: {} MDs to insert", mustAddList.size());
      moduleManager.createList(mustAddList, true, true, true, res1 -> {
        if (res1.failed()) {
          fut.handle(new Failure<>(res1.getType(), res1.cause()));
          return;
        }
        fut.handle(new Success<>(briefList));
      });
    });
  }

  void pull(PullDescriptor pd, Handler<ExtendedAsyncResult<List<ModuleDescriptor>>> fut) {
    getRemoteUrl(Arrays.asList(pd.getUrls()).iterator(), resUrl -> {
      if (resUrl.failed()) {
        fut.handle(new Failure<>(resUrl.getType(), resUrl.cause()));
        return;
      }
      moduleManager.getModulesWithFilter(true, true, null,
          resLocal -> {
            if (resLocal.failed()) {
              fut.handle(new Failure<>(resLocal.getType(), resLocal.cause()));
              return;
            }
            final String remoteUrl = resUrl.result().get(0);
            final String remoteVersion = resUrl.result().get(1);
            logger.info("Remote registry at {} is version {}", remoteUrl, remoteVersion);
            logger.info("pull smart");
            pullSmart(remoteUrl, resLocal.result(), fut);
          });
    });
  }
}
