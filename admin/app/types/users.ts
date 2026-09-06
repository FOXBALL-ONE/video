export interface UserRecord {
  user_id: number
  username: string
  email: string
  enabled: boolean
  roles: string[]
}

export interface UserListData {
  items: UserRecord[]
  total: number
  page: number
  size: number
  total_pages: number
}
