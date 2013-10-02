package models

import anorm._
import anorm.{NotAssigned, Pk}
import anorm.SqlParser
import model.Until
import play.api.Play.current
import play.api.db._
import scala.language.postfixOps
import collection.immutable.{LongMap, HashSet, HashMap, IntMap}
import java.sql.Connection
import play.api.data.Form
import org.joda.time.DateTime

case class ShoppingCartTotalEntry(
  shoppingCartItem: ShoppingCartItem,
  itemName: ItemName,
  itemDescription: ItemDescription,
  site: Site,
  itemPriceHistory: ItemPriceHistory,
  taxHistory: TaxHistory,
  itemNumericMetadata: Map[ItemNumericMetadataType, ItemNumericMetadata],
  siteItemNumericMetadata: Map[SiteItemNumericMetadataType, SiteItemNumericMetadata]
) extends NotNull {
  lazy val unitPrice: BigDecimal = itemPriceHistory.unitPrice
  lazy val quantity: Int = shoppingCartItem.quantity
  lazy val itemPrice: BigDecimal = unitPrice * quantity
}

case class ShoppingCartTotal(
  table: Seq[ShoppingCartTotalEntry]
) extends NotNull {
  lazy val size: Int = table.size
  lazy val notEmpty: Boolean = (! table.isEmpty)
  lazy val quantity: Int = table.foldLeft(0)(_ + _.quantity)
  lazy val total: BigDecimal = table.foldLeft(BigDecimal(0))(_ + _.itemPrice)
  lazy val sites: Seq[Site] = table.foldLeft(new HashSet[Site])(_ + _.site).toSeq
  lazy val taxTotal: BigDecimal = taxByType.values.foldLeft(BigDecimal(0))(_ + _)
  lazy val taxByType: Map[TaxType, BigDecimal] = {
    val sumById = table.foldLeft(LongMap().withDefaultValue(BigDecimal(0))) {
      (sum, e) => sum.updated(
        e.taxHistory.taxId,
        e.itemPriceHistory.unitPrice * e.shoppingCartItem.quantity + sum(e.taxHistory.taxId)
      )
    }
    val typeById = table.foldLeft(LongMap[TaxHistory]()) {
      (sum, e) => sum.updated(e.taxHistory.taxId, e.taxHistory)
    }

    sumById.foldLeft(HashMap[TaxType, BigDecimal]().withDefaultValue(BigDecimal(0))) {
      (sum, e) => {
        val taxHistory = typeById(e._1)
        val taxType = taxHistory.taxType
        sum.updated(taxType, sum(taxType) + taxHistory.taxAmount(e._2))
      }
    }
  }
  lazy val taxAmount: BigDecimal = taxByType.values.foldLeft(BigDecimal(0)){_ + _}

  def apply(index: Int): ShoppingCartTotalEntry = table(index)
}

case class ShoppingCart(
  items: Seq[ShoppingCartItem]
) extends NotNull

case class ShoppingCartItem(
  id: Pk[Long] = NotAssigned, storeUserId: Long, sequenceNumber: Int,
  siteId: Long, itemId: Long, quantity: Int
) extends NotNull

object ShoppingCartItem {
  val simple = {
    SqlParser.get[Pk[Long]]("shopping_cart_item.shopping_cart_item_id") ~
    SqlParser.get[Long]("shopping_cart_item.store_user_id") ~
    SqlParser.get[Int]("shopping_cart_item.seq") ~
    SqlParser.get[Long]("shopping_cart_item.site_id") ~
    SqlParser.get[Long]("shopping_cart_item.item_id") ~
    SqlParser.get[Int]("shopping_cart_item.quantity") map {
      case id~userId~seq~siteId~itemId~quantity =>
        ShoppingCartItem(id, userId, seq, siteId, itemId, quantity)
    }
  }

  def addItem(userId: Long, siteId: Long, itemId: Long, quantity: Int)(implicit conn: Connection): ShoppingCartItem = {
    SQL(
      """
      insert into shopping_cart_item (shopping_cart_item_id, store_user_id, seq, site_id, item_id, quantity)
      values (
        (select nextval('shopping_cart_item_seq')),
        {userId},
        (select coalesce(max(seq), 0) + 1 from shopping_cart_item where store_user_id = {userId}),
        {siteId},
        {itemId},
        {quantity}
      )
      """
    ).on(
      'userId ->userId,
      'siteId -> siteId,
      'itemId -> itemId,
      'quantity -> quantity
    ).executeUpdate()
    
    val id = SQL("select currval('shopping_cart_item_seq')").as(SqlParser.scalar[Long].single)
    val seq = SQL(
      "select seq from shopping_cart_item where shopping_cart_item_id = {id}"
    ).on('id -> id).as(SqlParser.scalar[Int].single)

    ShoppingCartItem(Id(id), userId, seq, siteId, itemId, quantity)
  }

  def remove(id: Long, userId: Long)(implicit conn: Connection): Int =
    SQL(
      """
      delete from shopping_cart_item
      where shopping_cart_item_id = {id} and store_user_id = {userId}
      """
    ).on(
      'id -> id,
      'userId -> userId
    ).executeUpdate()

  def removeForUser(userId: Long)(implicit conn: Connection) {
    SQL(
      "delete from shopping_cart_item where store_user_id = {id}"
    ).on(
      'id -> userId
    ).executeUpdate()
  }

  val listParser = ShoppingCartItem.simple~ItemName.simple~ItemDescription.simple~ItemPrice.simple~Site.simple map {
    case cart~itemName~itemDescription~itemPrice~site => (
      cart, itemName, itemDescription, itemPrice, site
    )
  }

  def listItemsForUser(
    locale: LocaleInfo, userId: Long, page: Int = 0, pageSize: Int = 10, now: Long = System.currentTimeMillis
  )(
    implicit conn: Connection
  ): ShoppingCartTotal = ShoppingCartTotal(
    SQL(
      """
      select * from shopping_cart_item
      inner join item_name on shopping_cart_item.item_id = item_name.item_id
      inner join item_description on shopping_cart_item.item_id = item_description.item_id
      inner join item_price on shopping_cart_item.item_id = item_price.item_id 
      inner join site_item on shopping_cart_item.item_id = site_item.item_id and item_price.site_id = site_item.site_id
      inner join site on site_item.site_id = site.site_id and shopping_cart_item.site_id = site.site_id
      where item_name.locale_id = {localeId}
      and item_description.locale_id = {localeId}
      and shopping_cart_item.store_user_id = {userId}
      order by seq
      limit {pageSize} offset {offset}
      """
    ).on(
      'localeId -> locale.id,
      'userId -> userId,
      'pageSize -> pageSize,
      'offset -> page * pageSize
    ).as(
      listParser *
    ).map { e =>
      val itemId = e._1.itemId
      val itemPriceId = e._4.id.get
      val priceHistory = ItemPriceHistory.at(itemPriceId, now)
      val taxHistory = TaxHistory.at(priceHistory.taxId, now)
      val metadata = ItemNumericMetadata.allById(itemId)
      val siteMetadata = SiteItemNumericMetadata.all(e._5.id.get, itemId)

      ShoppingCartTotalEntry(e._1, e._2, e._3, e._5, priceHistory, taxHistory, metadata, siteMetadata)
    }
  )

  def changeQuantity(id: Long, userId: Long, quantity: Int)(implicit conn: Connection): Int = {
    SQL(
      """
      update shopping_cart_item set quantity = {quantity}
      where shopping_cart_item_id = {id} and store_user_id = {userId}
      """
    ).on(
      'quantity -> quantity,
      'id ->id,
      'userId -> userId
    ).executeUpdate()
  }

  def apply(id: Long)(implicit conn: Connection): ShoppingCartItem =
    SQL(
      "select * from shopping_cart_item where shopping_cart_item_id = {id}"
    ).on(
      'id -> id
    ).as(simple.single)
}
