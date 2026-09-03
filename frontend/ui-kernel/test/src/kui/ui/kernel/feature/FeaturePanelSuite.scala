package kui.ui.kernel.feature

import scala.scalajs.js

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.ui.kernel.component.Mounted

final class FeaturePanelSuite extends FunSuite with Mounted {

  private val context = PanelContext(cluster = Some("production"), params = Map("topic" -> "orders"))

  /** A feature that contributes the given panels and nothing else. */
  private def featureWith(panelList: List[PanelContribution]): KuiFeature = new KuiFeature {
    def id: FeatureId = FeatureId.Clusters
    def nav: NavEntry = NavEntry(FeatureId.Clusters, "Clusters", () => svg.svg(), 0, requiresCluster = false)
    def routes: List[com.raquo.waypoint.Route[? <: Page, ?]]                          = Nil
    def render(page: Page): HtmlElement                                               = div()
    def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement = div(reason.message)
    override def panels: List[PanelContribution]                                      = panelList
  }

  private def panel(slot: String, label: String): PanelContribution =
    PanelContribution(FeatureId.Clusters, slot, ctx => div(dataAttr("testid") := label, ctx.cluster.getOrElse("")))

  test("aHostRendersEveryRegisteredPanelForItsSlot") {
    val features = Val(List(featureWith(List(panel("topic.tabs", "first"), panel("topic.tabs", "second")))))

    mounted(FeaturePanel(features, FeatureId.Clusters, "topic.tabs", context)) { root =>
      assertEquals(byTestId(root, "first").textContent, "production")
      assertEquals(byTestId(root, "second").textContent, "production")
    }
  }

  test("a panel registered for another slot is not rendered") {
    val features = Val(List(featureWith(List(panel("broker.tabs", "elsewhere")))))

    mounted(FeaturePanel(features, FeatureId.Clusters, "topic.tabs", context)) { root =>
      assertEquals(Option(root.querySelector("[data-testid='elsewhere']")), None)
    }
  }

  test("anEmptySlotRendersNothingAndDoesNotThrow") {
    mounted(FeaturePanel(Val(Nil), FeatureId.Clusters, "topic.tabs", context)) { root =>
      assertEquals(root.childElementCount, 0)
      assertEquals(attributeOf(root, "data-kui-slot"), Some("topic.tabs"))
    }
  }

  test("aPanelFromAnUnloadedFeatureIsNotRenderedAndDoesNotTriggerALoad") {
    // The subtle one, and the reason the signature takes loaded features rather than a registry: a
    // naive implementation that asked "who contributes to this slot?" would make every topic page
    // download the consumers feature, for every user, including those who cannot see consumers.
    var imports = 0
    val registry = new Features(
      Map(FeatureId.Clusters -> { () =>
        imports += 1
        js.Promise.resolve[KuiFeature](featureWith(List(panel("topic.tabs", "consumers"))))
      })
    )

    mounted(FeaturePanel(registry.loaded, FeatureId.Clusters, "topic.tabs", context)) { root =>
      assertEquals(imports, 0, "rendering a host slot must not import anything")
      assertEquals(Option(root.querySelector("[data-testid='consumers']")), None)
    }
  }

  test("a panel appears once its feature has loaded for some other reason") {
    val features = Var(List.empty[KuiFeature])

    mounted(FeaturePanel(features.signal, FeatureId.Clusters, "topic.tabs", context)) { root =>
      assertEquals(root.childElementCount, 0)

      features.set(List(featureWith(List(panel("topic.tabs", "consumers")))))
      assertEquals(byTestId(root, "consumers").textContent, "production")
    }
  }

  test("panels are ordered by their contributing feature's nav order, not by load order") {
    // Otherwise two panels in one slot swap places depending on which module finished downloading
    // first, which is a layout that changes on a slow connection and not on a fast one.
    def orderedFeature(order: Int, label: String): KuiFeature = new KuiFeature {
      def id: FeatureId = FeatureId.Clusters
      def nav: NavEntry = NavEntry(FeatureId.Clusters, label, () => svg.svg(), order, requiresCluster = false)
      def routes: List[com.raquo.waypoint.Route[? <: Page, ?]]                          = Nil
      def render(page: Page): HtmlElement                                               = div()
      def unavailableView(reason: UnavailableReason, retry: Observer[Unit]): HtmlElement = div(reason.message)
      override def panels: List[PanelContribution]                                      = List(panel("topic.tabs", label))
    }

    val features = Val(List(orderedFeature(1, "first"), orderedFeature(2, "second")))

    mounted(FeaturePanel(features, FeatureId.Clusters, "topic.tabs", context)) { root =>
      val labels = root.children.toList.collect { case element: dom.Element => attributeOf(element, "data-testid") }

      assertEquals(labels, List(Some("first"), Some("second")))
    }
  }
}
