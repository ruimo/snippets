package models

import org.specs2.mutable._

import play.api.test._
import play.api.test.Helpers._
import play.api.db.DB
import play.api.Play.current
import anorm.Id

class UserSpec extends Specification {
  "User" should {
    "User count should be zero when table is empty" in {
      running(FakeApplication(additionalConfiguration = inMemoryDatabase())) {
        DB.withConnection { implicit conn => {
          StoreUser.count === 0
        }}
      }
    }

    "User count should reflect the number of records in the table" in {
      running(FakeApplication(additionalConfiguration = inMemoryDatabase())) {
        DB.withConnection { implicit conn => {
          StoreUser.create(
            "userName", "firstName", Some("middleName"), "lastName", "email",
            1L, 2L, UserRole.ADMIN, Some("companyName")
          )
          StoreUser.count === 1
        }}
      }
    }

    "User can be queried by username" in {
      running(FakeApplication(additionalConfiguration = inMemoryDatabase())) {
        DB.withConnection { implicit conn => {
          val user1 = StoreUser.create(
            "userName", "firstName", Some("middleName"), "lastName", "email",
            1L, 2L, UserRole.ADMIN, Some("companyName")
          )

          val user2 = StoreUser.create(
            "userName2", "firstName2", None, "lastName2", "email2",
            1L, 2L, UserRole.ADMIN, None
          )

          StoreUser.findByUserName("userName").get === user1
          StoreUser.findByUserName("userName2").get === user2
        }}
      }
    }

    "Can delete user" in {
      running(FakeApplication(additionalConfiguration = inMemoryDatabase())) {
        DB.withConnection { implicit conn =>
          val user1 = StoreUser.create(
            "userName", "firstName", Some("middleName"), "lastName", "email",
            1L, 2L, UserRole.ADMIN, Some("companyName")
          )

          val user2 = StoreUser.create(
            "userName2", "firstName2", None, "lastName2", "email2",
            1L, 2L, UserRole.ADMIN, None
          )

          StoreUser.listUsers().size === 2
          StoreUser.delete(user2.id.get)
          val list = StoreUser.listUsers()
          list.size === 1
          list(0)._1 === user1
        }
      }
    }

    "listUsers list user ordered by user name" in {
      running(FakeApplication(additionalConfiguration = inMemoryDatabase())) {
        DB.withConnection { implicit conn =>
          TestHelper.removePreloadedRecords()

          val user1 = StoreUser.create(
            "userName", "firstName", Some("middleName"), "lastName", "email",
            1L, 2L, UserRole.ADMIN, Some("companyName")
          )

          val user2 = StoreUser.create(
            "userName2", "firstName2", None, "lastName2", "email2",
            1L, 2L, UserRole.ADMIN, None
          )
          val site1 = Site.createNew(LocaleInfo.Ja, "商店1")

          val siteUser = SiteUser.createNew(user2.id.get, site1.id.get)

          val list = StoreUser.listUsers()
          list.size === 2
          list(0)._1 === user1
          list(0)._2 === None

          list(1)._1 === user2
          list(1)._2.get === siteUser
        }
      }
    }
  }
}

