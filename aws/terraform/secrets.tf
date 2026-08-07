resource "random_password" "postgres" {
  length  = 24
  special = false
}
