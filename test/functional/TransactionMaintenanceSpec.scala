package functional

import anorm._
import play.api.db._
import play.api.test._
import play.api.test.Helpers._
import play.api.Play.current

import functional.Helper._

import org.specs2.mutable.Specification
import play.api.test.{Helpers, TestServer, FakeApplication}
import play.api.i18n.{Lang, Messages}
import play.api.test.TestServer
import play.api.test.FakeApplication
import play.api.db.DB
import models._
import java.sql.Date.{valueOf => date}
import play.api.test.TestServer
import play.api.test.FakeApplication
import LocaleInfo._
import java.sql.Connection
import org.openqa.selenium.By
import org.joda.time.format.DateTimeFormat
import org.joda.time.LocalTime

class TransactionMaintenanceSpec extends Specification {
  implicit def date2milli(d: java.sql.Date) = d.getTime

  "Transaction maitenance" should {
    "Validation error will shown" in {
      val app = FakeApplication(additionalConfiguration = inMemoryDatabase())
      running(TestServer(3333, app), Helpers.HTMLUNIT) { browser => DB.withConnection { implicit conn =>
        implicit val lang = Lang("ja")
        val tax = Tax.createNew
        val taxHis = TaxHistory.createNew(tax, TaxType.INNER_TAX, BigDecimal("5"), date("9999-12-31"))
        val user = loginWithTestUser(browser)

        val site1 = Site.createNew(Ja, "商店1")
        val site2 = Site.createNew(En, "Shop2")

        val cat1 = Category.createNew(
          Map(Ja -> "植木", En -> "Plant")
        )

        val item1 = Item.createNew(cat1)
        val item2 = Item.createNew(cat1)
        val item3 = Item.createNew(cat1)

        SiteItem.createNew(site1, item1)
        SiteItem.createNew(site2, item2)
        SiteItem.createNew(site1, item3)

        val itemName1 = ItemName.createNew(item1, Map(Ja -> "植木1"))
        val itemName2 = ItemName.createNew(item2, Map(Ja -> "植木2"))
        val itemName3 = ItemName.createNew(item3, Map(Ja -> "植木3"))

        val itemDesc1 = ItemDescription.createNew(item1, site1, "desc1")
        val itemDesc2 = ItemDescription.createNew(item2, site2, "desc2")
        val itemDesc3 = ItemDescription.createNew(item3, site1, "desc3")

        val itemPrice1 = ItemPrice.createNew(item1, site1)
        val itemPrice2 = ItemPrice.createNew(item2, site2)
        val itemPrice3 = ItemPrice.createNew(item3, site1)

        val itemPriceHis1 = ItemPriceHistory.createNew(
          itemPrice1, tax, CurrencyInfo.Jpy, BigDecimal("100"), date("9999-12-31")
        )
        val itemPriceHis2 = ItemPriceHistory.createNew(
          itemPrice2, tax, CurrencyInfo.Jpy, BigDecimal("200"), date("9999-12-31")
        )
        val itemPriceHis3 = ItemPriceHistory.createNew(
          itemPrice3, tax, CurrencyInfo.Jpy, BigDecimal("300"), date("9999-12-31")
        )

        val shoppingCartItem1 = ShoppingCartItem.addItem(user.id.get, site1.id.get, item1.id.get, 3)
        val shoppingCartItem2 = ShoppingCartItem.addItem(user.id.get, site2.id.get, item2.id.get, 5)
        val shoppingCartItem3 = ShoppingCartItem.addItem(user.id.get, site1.id.get, item3.id.get, 7)

        val shoppingCartTotal1 = List(
          ShoppingCartTotalEntry(
            shoppingCartItem1,
            itemName1(Ja),
            itemDesc1,
            site1,
            itemPriceHis1,
            taxHis
          )
        )

        val addr1 = Address.createNew(
          countryCode = CountryCode.JPN,
          firstName = "firstName1",
          lastName = "lastName1",
          zip1 = "zip1",
          zip2 = "zip2",
          prefecture = JapanPrefecture.東京都,
          address1 = "address1-1",
          address2 = "address1-2",
          tel1 = "tel1-1",
          comment = "comment1"
        )

        val transporter1 = Transporter.createNew
        val transporter2 = Transporter.createNew
        val transporterName1 = TransporterName.createNew(
          transporter1.id.get, LocaleInfo.Ja, "トマト運輸"
        )
        val transporterName2 = TransporterName.createNew(
          transporter2.id.get, LocaleInfo.Ja, "ヤダワ急便"
        )

        val box1 = ShippingBox.createNew(site1.id.get, 1L, 3, "site-box1")
        val box2 = ShippingBox.createNew(site2.id.get, 1L, 2, "site-box2")

        val fee1 = ShippingFee.createNew(box1.id.get, CountryCode.JPN, JapanPrefecture.東京都.code)
        val fee2 = ShippingFee.createNew(box2.id.get, CountryCode.JPN, JapanPrefecture.東京都.code)

        val feeHis1 = ShippingFeeHistory.createNew(
          fee1.id.get, tax.id.get, BigDecimal(123), date("9999-12-31")
        )
        val feeHis2 = ShippingFeeHistory.createNew(
          fee2.id.get, tax.id.get, BigDecimal(234), date("9999-12-31")
        )
        val now = System.currentTimeMillis

        val shippingTotal1 = ShippingFeeHistory.feeBySiteAndItemClass(
          CountryCode.JPN, JapanPrefecture.東京都.code,
          ShippingFeeEntries().add(site1, 1L, 3),
          now
        )
        val shippingDate1 = ShippingDate(Map(site1.id.get -> ShippingDateEntry(site1.id.get, date("2013-02-03"))))

        val cartTotal1 = ShoppingCartItem.listItemsForUser(LocaleInfo.Ja, user.id.get)
        (new TransactionPersister).persist(
          Transaction(user.id.get, CurrencyInfo.Jpy, cartTotal1, addr1, shippingTotal1, shippingDate1, now)
        )

        browser.goTo(
          "http://localhost:3333" + controllers.routes.TransactionMaintenance.index + "?lang=" + lang.code
        )
        browser.title === Messages("transactionMaintenanceTitle")
        browser.find(".site").getText === "商店1"
        browser.find(".shippingDate").getText === "2013年02月03日"
        browser.find(".transactionAmount").getText === "2,400円"
        browser.find(".transactionShipping").getText === "123円"
        browser.find(".buyer").find(".companyName").getText === user.companyName.get
        browser.find(".buyer").find(".name").getText === user.firstName + " " + user.lastName
        browser.find(".buyer").find(".email").getText === user.email
        browser.find(".shippingTableBody").find(".zip").getText === "zip1 - zip2"
        browser.find(".shippingTableBody").find(".prefecture").getText === JapanPrefecture.東京都.toString
        browser.find(".shippingTableBody").find(".address1").getText === "address1-1"
        browser.find(".shippingTableBody").find(".address2").getText === "address1-2"
        browser.find(".shippingTableBody").find(".tel1").getText === "tel1-1"
        browser.find(".shippingTableBody").find(".comment").getText === "comment1"
        browser.webDriver
          .findElement(By.id("status")).findElement(By.cssSelector("option[value=\"0\"]")).isSelected === true

        browser
          .find("#transporterId")
          .find("option[value=\"" + transporter1.id.get + "\"]").getText === transporterName1.transporterName
        browser
          .find("#transporterId")
          .find("option[value=\"" + transporter2.id.get + "\"]").getText === transporterName2.transporterName

        browser
          .find("#transporterId")
          .find("option[value=\"" + transporter2.id.get + "\"]").click()

        // Input error(slip code is not filled).
        browser.find(".changeShippingInfoButton").click()
        browser.find("#slipCode_field").find(".error").getText === Messages("error.required")

        browser.fill("#slipCode").`with`("12345678")
        browser.find(".changeShippingInfoButton").click()

        browser.title === Messages("transactionMaintenanceTitle")
        browser.webDriver
          .findElement(By.id("status")).findElement(By.cssSelector("option[value=\"1\"]")).isSelected === true
        browser.find(".shippingStatusTable").find(".transporter").getText === transporterName2.transporterName
        browser.find(".shippingStatusTable").find(".slipCode").getText === "12345678"

        // Cancel
        browser.webDriver
          .findElement(By.id("status")).findElement(By.cssSelector("option[value=\"2\"]")).click()
        browser.find("#changeShippingStatusButton").click();

        browser.webDriver
          .findElement(By.id("status")).findElement(By.cssSelector("option[value=\"2\"]")).isSelected === true
      }}
    }
  }
}
