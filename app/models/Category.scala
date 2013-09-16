package models

import anorm._
import anorm.{NotAssigned, Pk}
import anorm.SqlParser
import play.api.Play.current
import play.api.db._
import scala.language.postfixOps
import play.api.i18n.Lang
import java.sql.Connection

case class CategoryPath(ancestor: Long, descendant: Long, pathLength: Int) extends NotNull {
  assert(pathLength >= 0 && pathLength <= Short.MaxValue, "length(= " + pathLength + ") is invalid.")
}

case class CategoryName(locale: LocaleInfo, categoryId: Long, name: String) extends NotNull {
  assert(name != null && name.length <= 32, "length(= " + name + ") is invalid.")
}

case class Category(id: Pk[Long] = NotAssigned) extends NotNull

object Category {
  val simple = {
    SqlParser.get[Pk[Long]]("category.category_id") map {
      case id => Category(id)
    }
  }

  def tableForDropDown(implicit lang: Lang, conn: Connection): Seq[(String, String)] = {
    val locale = LocaleInfo.byLang(lang)

    SQL(
      """
      select * from category
      inner join category_name on category.category_id = category_name.category_id
      where locale_id = {localeId}
      order by category_name.category_name
      """
    ).on(
      'localeId -> locale.id
    ).as(
      withName *
    ).map {
      e => e._1.id.get.toString -> e._2.name
    }
  }

  def root(implicit conn: Connection): Seq[Category] = SQL(
    """
    select * from category c
    where not exists (
    select 'X' from category_path p
    where c.category_id = p.descendant
    and c.category_id <> p.ancestor
      )
    """
  ).as(Category.simple *)

  val withName = Category.simple ~ CategoryName.simple map {
    case cat~name => (cat, name)
  }

  def list(
    page: Int = 0, pageSize: Int = 10, locale: LocaleInfo
  )(implicit conn: Connection): Page[(Category, CategoryName)] = {
    val offset = pageSize * page
    val resultList = SQL(
      """
      select * from category
      inner join category_name on category.category_id = category_name.category_id
      where locale_id = {localeId}
      order by category_name.category_name
      limit {pageSize} offset {offset}
      """
    ).on(
      'localeId -> locale.id,
      'pageSize -> pageSize,
      'offset -> offset
    ).as(withName *)

    val count = SQL(
      """
      select count(*) from category
      """
    ).as(SqlParser.scalar[Long].single)

    Page(resultList, page, offset, count)
  }

  def createNew(names: Map[LocaleInfo, String])(implicit conn: Connection): Category = createNew(None, names)
  def createNew(parent: Category, names: Map[LocaleInfo, String])(implicit conn: Connection): Category = createNew(Some(parent), names)
  def createNew(
    parent: Option[Category], names: Map[LocaleInfo, String]
  )(implicit conn: Connection): Category = {
    SQL(
      """
      insert into category values (
        (select nextval('category_seq'))
      )
      """
    ).executeUpdate()

    val categoryId = SQL("select currval('category_seq')").as(SqlParser.scalar[Long].single)

    names.foreach { e =>
      SQL(
        """
        insert into category_name
          (locale_id, category_name, category_id)
          values
          ({locale_id}, {category_name}, {category_id})
        """
      ).on(
        'locale_id -> e._1.id,
        'category_name -> e._2,
        'category_id -> categoryId
      ).executeUpdate()
    }

    /*
      cat1(id1) -+- cat2(id2)
                 +- cat3(id3)
    
      |----------+------------+-------------|
      | ancestor | descendant | path_length |
      |----------+------------+-------------|
      | id1      | id1        |           0 |
      | id1      | id2        |           1 |
      | id1      | id3        |           1 |
      | id2      | id2        |           0 |
      | id3      | id3        |           0 |
      |----------+------------+-------------|

      Add cat(id4) under cat3(id3)

      cat1(id1) -+- cat2(id2)
                 +- cat3(id3) - cat4(id4)
                 
      The following records should be created.
      |----------+------------+-------------|
      | ancestor | descendant | path_length |
      |----------+------------+-------------|
      | id4      | id4        |           0 |
      | id3      | id4        |           1 |
      | id1      | id4        |           2 |
      |----------+------------+-------------|

      */

    SQL(
      """
      insert into category_path
        (ancestor, descendant, path_length)
        values
        ({category_id}, {category_id}, 0)
      """
    ).on(
      'category_id -> categoryId
    ).executeUpdate()

    parent.map {cat => {
      SQL(
        """
        insert into category_path
          (ancestor, descendant, path_length)
          select ancestor, {category_id}, path_length + 1
          from category_path
          where descendant = {descendant}
        """
      ).on(
        'descendant -> cat.id.get,
        'category_id -> categoryId
      ).executeUpdate()
    }}

    Category(Id(categoryId))
  }
}

object CategoryName {
  val simple = {
    SqlParser.get[Long]("category_name.locale_id") ~
    SqlParser.get[Long]("category_name.category_id") ~
    SqlParser.get[String]("category_name.category_name") map {
      case localeId~categoryId~categoryName =>
        CategoryName(LocaleInfo(localeId), categoryId, categoryName)
    }
  }

  def get(locale: LocaleInfo, category: Category)(implicit conn: Connection): String = get(locale, category.id.get)
  def get(locale: LocaleInfo, categoryId: Long)(implicit conn: Connection): String = 
    SQL(
      """
      select category_name from category_name
      where category_id = {category_id} and locale_id = {locale_id}
      """
    ).on(
      'category_id -> categoryId,
      'locale_id -> locale.id
    ).as(SqlParser.scalar[String].single)
}

object CategoryPath {
  val simple = {
    SqlParser.get[Long]("category_path.ancestor") ~
    SqlParser.get[Long]("category_path.descendant") ~
    SqlParser.get[Int]("category_path.path_length") map {
      case ancestor~descendant~pathLength =>
        CategoryPath(ancestor, descendant, pathLength)
    }
  }

  val child = {
    SqlParser.get[Pk[Long]]("category_path.descendant") map {
      case descendant => Category(descendant)
    }
  }

  val withName = child ~ CategoryName.simple map {
    case cat~name => (cat, name)
  }

  def parent(category: Category)(implicit conn: Connection): Option[Category] = parentById(category.id.get)
  def parentById(categoryId: Long)(implicit conn: Connection): Option[Category] = 
    SQL(
      """
      select ancestor from category_path
      where descendant = {category_id} and ancestor <> {category_id}
      """
    ).on(
      'category_id -> categoryId
    ).as(
      SqlParser.scalar[Pk[Long]].singleOpt
    ).map(Category(_))

  def children(
    category: Category, depth: Int = 1
  )(implicit conn: Connection): Seq[Category] = childrenById(category.id.get, depth)
  def childrenById(categoryId: Long, depth: Int = 1)(implicit conn: Connection): Seq[Category] = 
    SQL(
      """
      select * from category_path
      where ancestor = {ancestor} and path_length = {path_length}
      """
    ).on(
      'ancestor -> categoryId,
      'path_length -> depth
    ).as(child *)

  def childrenNames(
    category: Category, locale: LocaleInfo, depth: Int = 1
  )(implicit conn: Connection): Seq[(Category, CategoryName)] =
    childrenNamesById(category.id.get, locale, depth)

  def childrenNamesById(
    categoryId: Long, locale: LocaleInfo, depth: Int = 1
  )(implicit conn: Connection): Seq[(Category, CategoryName)] =
    SQL(
      """
      select * from category_path inner join category_name
      on category_path.descendant = category_name.category_id
      where category_path.ancestor = {ancestor}
      and category_path.path_length = {path_length}
      and category_name.locale_id = {locale_id}
      """
    ).on(
      'ancestor -> categoryId,
      'path_length -> depth,
      'locale_id -> locale.id
    ).as(withName *)
}
