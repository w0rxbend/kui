package kui.ui.shell.page

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.Card
import kui.ui.shell.ShellCss

/** The dashboard: the first screen anybody sees, and the one that has to be true.
  *
  * There are no cluster summary cards here yet. Those are UI-012 and are genuinely not built, so this page
  * says so rather than showing an empty frame that looks broken.
  *
  * What it must not do is what it did until 2026-09-04, which was to say that "cluster overviews appear here
  * once the clusters feature is installed". That sentence was written in M0, when it was accurate. By M4 the
  * clusters feature was installed, working, and one click away — and this page, the very first thing a
  * newcomer sees after running the quickstart, was telling them the product was not there. Nothing was broken
  * except the message.
  *
  * So the page names what is missing and points at what is not. The link is a plain `a` and not a router link
  * on purpose: `/clusters` belongs to another feature, and naming that feature's page type here would pull
  * its whole module into `main.js` for every user, which is what `checkBundleShape` forbids.
  *
  * @param uiPrefix
  *   where the application is mounted, without a trailing slash — `/ui` by default, more when a reverse proxy
  *   mounts KUI under a path. The link is built from it rather than hard-coded for the same reason every
  *   other URL in the shell is.
  */
object HomePage {

  def apply(uiPrefix: String): HtmlElement =
    div(
      cls := ShellCss.Page,
      dataAttr("testid") := "page-home",
      h1("KUI"),
      Card(
        header = Some(h2("Start with your clusters")),
        body = div(
          p(
            "Every cluster KUI is configured with, whether it is answering or not, is on the ",
            a(href := s"$uiPrefix/clusters", "Clusters"),
            " page. From a cluster you can reach its brokers, its topics, the records inside them and ",
            "its consumer groups."
          ),
          p(
            "A dashboard summarising every cluster at once is not built yet, which is why this page ",
            "is a signpost rather than a summary."
          )
        )
      )
    )
}
