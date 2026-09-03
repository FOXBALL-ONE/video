package top.foxball.video.entity.jdbc

import jakarta.persistence.*

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
    
    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String = "",
    
    @Column(nullable = false)
    var enabled: Boolean = true,
    
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var roles: MutableSet<Role> = mutableSetOf(),
)
