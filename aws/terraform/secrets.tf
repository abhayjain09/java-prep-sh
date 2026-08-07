resource "random_password" "postgres" {
  length  = 24
  special = false
}

resource "aws_secretsmanager_secret" "postgres_password" {
  name = "${local.name_prefix}/postgres/password"

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-postgres-password"
  })
}

resource "aws_secretsmanager_secret_version" "postgres_password" {
  secret_id     = aws_secretsmanager_secret.postgres_password.id
  secret_string = random_password.postgres.result
}
