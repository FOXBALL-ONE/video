package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 系统用户实体，映射到 users 表。 */
@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库自增主键。 */
    var id: Long? = null,
    /** 登录用户名。 */
    @Column(nullable = false, unique = true, length = 50)
    var username: String = "",
    @Column(nullable = false, unique = true, length = 255)
    /** 用户邮箱地址。 */
    var email: String = "",
)
