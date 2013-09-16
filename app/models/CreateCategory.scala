package models

import play.api.db.DB
import play.api.Play.current

case class CreateCategory(localeId: Long, categoryName: String) {
  def save() {
    DB.withConnection { implicit conn =>
      Category.createNew(Map(LocaleInfo(localeId) -> categoryName))
    }
  }
}

