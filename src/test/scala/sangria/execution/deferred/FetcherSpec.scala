package sangria.execution.deferred

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.{Matchers, WordSpec}
import sangria.ast
import sangria.execution.{DeferredWithInfo, Executor, HandledException}
import sangria.macros._
import sangria.schema._
import sangria.util.{DebugUtil, FutureResultSupport, Pos}
import sangria.util.SimpleGraphQlSupport._

import scala.concurrent.{ExecutionContext, Future}

class FetcherSpec extends WordSpec with Matchers with FutureResultSupport {
  case class Category(id: String, name: String, children: Seq[String])
  case class ColorDeferred(id: String) extends Deferred[String]

  object Category {
    implicit val hasId = HasId[Category, String](_.id)
  }

  class CategoryRepo {
    private val categories = Vector(
      Category("1", "Root", Vector("2", "3", "4")),
      Category("2", "Cat 2", Vector("5", "6")),
      Category("3", "Cat 3", Vector("7", "5", "6")),
      Category("4", "Cat 4", Vector.empty),
      Category("5", "Cat 5", Vector.empty),
      Category("6", "Cat 6", Vector.empty),
      Category("7", "Cat 7", Vector.empty),
      Category("8", "Cat 8", Vector("4", "5", "foo!")),
      Category("20", "Cat 8", (1 to 8).map(_.toString)))

    def loadBulk(ids: Seq[String])(implicit ec: ExecutionContext): Future[Seq[Category]] =
      Future(ids.flatMap(id ⇒ categories.find(_.id == id)))

    def get(id: String)(implicit ec: ExecutionContext) =
      Future(categories.find(_.id == id))
  }

  def properFetcher(implicit ec: ExecutionContext) = {
    def schema(fetcher: Fetcher[CategoryRepo, String, Category]) = {
      lazy val CategoryType: ObjectType[CategoryRepo, Category] = ObjectType("Category", () ⇒ fields(
        Field("id", StringType, resolve = c ⇒ c.value.id),
        Field("name", StringType, resolve = c ⇒ c.value.name),
        Field("color", StringType, resolve = c ⇒ ColorDeferred("red")),
        Field("self", CategoryType, resolve = c ⇒ c.value),
        Field("selfOpt", OptionType(CategoryType), resolve = c ⇒ Some(c.value)),
        Field("selfFut", CategoryType, resolve = c ⇒ Future(c.value)),
        Field("categoryNonOpt", CategoryType,
          arguments = Argument("id", StringType) :: Nil,
          resolve = c ⇒ fetcher.defer(c.arg[String]("id"))),
        Field("childrenSeq", ListType(CategoryType),
          resolve = c ⇒ fetcher.deferSeq(c.value.children)),
        Field("childrenSeqOpt", ListType(CategoryType),
          resolve = c ⇒ fetcher.deferSeqOpt(c.value.children)),
        Field("childrenFut", ListType(CategoryType),
          resolve = c ⇒ DeferredFutureValue(Future.successful(
            fetcher.deferSeq(c.value.children))))))

      val QueryType = ObjectType("Query", fields[CategoryRepo, Unit](
        Field("category", OptionType(CategoryType),
          arguments = Argument("id", StringType) :: Nil,
          resolve = c ⇒ fetcher.deferOpt(c.arg[String]("id"))),
        Field("categoryEager", OptionType(CategoryType),
          arguments = Argument("id", StringType) :: Nil,
          resolve = c ⇒ c.ctx.get(c.arg[String]("id"))),
        Field("categoryNonOpt", CategoryType,
          arguments = Argument("id", StringType) :: Nil,
          resolve = c ⇒ fetcher.defer(c.arg[String]("id"))),
        Field("root", CategoryType, resolve = _ ⇒ fetcher.defer("1")),
        Field("rootFut", CategoryType, resolve = _ ⇒
          DeferredFutureValue(Future.successful(fetcher.defer("1"))))))

      Schema(QueryType)
    }

    "fetch results in batches and cache results is necessary" in {
      val query =
        graphql"""
          {
            c1: category(id: "non-existing") {name}
            c3: category(id: "8") {name childrenSeqOpt {id}}

            root {
              id
              name
              childrenSeq {
                id
                name
                childrenSeq {
                  id
                  name
                  childrenSeq {
                    id
                    name
                    childrenSeq {
                      id
                      name
                    }
                  }
                }
              }
            }
          }
        """

      var fetchedIds = Vector.empty[Seq[String]]

      val fetcher =
        Fetcher((repo: CategoryRepo, ids: Seq[String]) ⇒ {
          fetchedIds = fetchedIds :+ ids

          repo.loadBulk(ids)
        })

      var fetchedIdsCached = Vector.empty[Seq[String]]

      val fetcherCached =
        Fetcher.caching((repo: CategoryRepo, ids: Seq[String]) ⇒ {
          fetchedIdsCached = fetchedIdsCached :+ ids

          repo.loadBulk(ids)
        })


      val res = Executor.execute(schema(fetcher), query, new CategoryRepo,
        deferredResolver = DeferredResolver.fetchers(fetcher)).await

      val resCached = Executor.execute(schema(fetcherCached), query, new CategoryRepo,
        deferredResolver = DeferredResolver.fetchers(fetcherCached)).await

      fetchedIds should be (Vector(
        Vector("1", "non-existing", "8"),
        Vector("3", "4", "5", "2", "foo!"),
        Vector("5", "6", "7")))

      fetchedIdsCached should be (Vector(
        Vector("1", "non-existing", "8"),
        Vector("3", "4", "5", "2", "foo!"),
        Vector("6", "7")))

      List(res, resCached) foreach (_ should be (
        Map(
          "data" → Map(
            "c1" → null,
            "c3" → Map(
              "name" → "Cat 8",
              "childrenSeqOpt" → Vector(
                Map(
                  "id" → "4"),
                Map(
                  "id" → "5"))),
            "root" → Map(
              "id" → "1",
              "name" → "Root",
              "childrenSeq" → Vector(
                Map(
                  "id" → "2",
                  "name" → "Cat 2",
                  "childrenSeq" → Vector(
                    Map(
                      "id" → "5",
                      "name" → "Cat 5",
                      "childrenSeq" → Vector.empty),
                    Map(
                      "id" → "6",
                      "name" → "Cat 6",
                      "childrenSeq" → Vector.empty))),
                Map(
                  "id" → "3",
                  "name" → "Cat 3",
                  "childrenSeq" → Vector(
                    Map(
                      "id" → "7",
                      "name" → "Cat 7",
                      "childrenSeq" → Vector.empty),
                    Map(
                      "id" → "5",
                      "name" → "Cat 5",
                      "childrenSeq" → Vector.empty),
                    Map(
                      "id" → "6",
                      "name" → "Cat 6",
                      "childrenSeq" → Vector.empty))),
                Map(
                  "id" → "4",
                  "name" → "Cat 4",
                  "childrenSeq" → Vector.empty)))))))
    }

    "should result in error for missing non-optional values" in {
      var fetchedIds = Vector.empty[Seq[String]]

      val fetcher =
        Fetcher((repo: CategoryRepo, ids: Seq[String]) ⇒ {
          fetchedIds = fetchedIds :+ ids

          repo.loadBulk(ids)
        })

      checkContainsErrors(schema(fetcher), (),
        """
          {
            c1: category(id: "8") {name childrenSeq {id}}
            c2: categoryEager(id: "1") {
              name
              selfOpt {
                categoryNonOpt(id: "qwe") {name}
              }
            }
          }
        """,
        Map(
          "c1" → null,
          "c2" → Map(
            "name" → "Root",
            "selfOpt" → null)),
        List(
          "Fetcher has not resolved non-optional ID 'foo!'." → List(Pos(3, 41)),
          "Fetcher has not resolved non-optional ID 'qwe'." → List(Pos(7, 17))),
        resolver = DeferredResolver.fetchers(fetcher),
        userContext = new CategoryRepo)

      fetchedIds should be (Vector(
        Vector("8", "qwe"),
        Vector("4", "5", "foo!")))
    }

    "use fallback `DeferredResolver`" in {
      class MyDeferredResolver extends DeferredResolver[Any] {
        val callsCount = new AtomicInteger(0)
        val valueCount = new AtomicInteger(0)

        override val includeDeferredFromField: Option[(Field[_, _], Vector[ast.Field], Args, Double) ⇒ Boolean] =
          Some((_, _, _, _) ⇒ false)

        def resolve(deferred: Vector[Deferred[Any]], ctx: Any, queryState: Any)(implicit ec: ExecutionContext) = {
          callsCount.getAndIncrement()
          valueCount.addAndGet(deferred.size)

          deferred.map {
            case ColorDeferred(id) ⇒ Future.successful(id + "Color")
          }
        }
      }

      var fetchedIds = Vector.empty[Seq[String]]

      val fetcher =
        Fetcher((repo: CategoryRepo, ids: Seq[String]) ⇒ {
          fetchedIds = fetchedIds :+ ids

          repo.loadBulk(ids)
        })

      check(schema(fetcher), (),
        """
          {
            c1: category(id: "1") {name childrenSeq {id}}


            c2: categoryEager(id: "2") {
              color
              childrenSeq {name}
            }
          }
        """,
        Map(
          "data" → Map(
            "c1" → Map(
              "name" → "Root",
              "childrenSeq" → Vector(
                Map("id" → "2"),
                Map("id" → "3"),
                Map("id" → "4"))),
            "c2" → Map(
              "color" → "redColor",
              "childrenSeq" → Vector(
                Map("name" → "Cat 5"),
                Map("name" → "Cat 6"))))),
        resolver = DeferredResolver.fetchersWithFallback(new MyDeferredResolver, fetcher),
        userContext = new CategoryRepo)

      fetchedIds should (
        have(size(3)) and
        contain(Vector("1")) and
        contain(Vector("5", "6")) and
        contain(Vector("3", "4", "2")))
    }

    "explicit cache should be used in consequent executions" in pending
    "support multiple fetchers" in (pending)
  }

  "Fetcher" when {
    "using standard execution context" should {
      behave like properFetcher (ExecutionContext.Implicits.global)
    }

    "using sync execution context" should {
      behave like properFetcher (sync.executionContext)
    }
  }
}