resource "random_id" "cognito_domain_suffix" {
  byte_length = 3
}

resource "aws_route53_zone" "app" {
  count = local.sso_enabled && var.existing_route53_zone_id == null && var.create_route53_zone ? 1 : 0

  name = local.root_domain

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-public-zone"
  })
}

resource "aws_acm_certificate" "app" {
  count = local.sso_enabled ? 1 : 0

  domain_name       = local.app_domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-app-cert"
  })
}

resource "aws_route53_record" "app_certificate_validation" {
  for_each = local.sso_enabled ? {
    for option in aws_acm_certificate.app[0].domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  } : {}

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = var.existing_route53_zone_id != null ? var.existing_route53_zone_id : aws_route53_zone.app[0].zone_id
}

resource "aws_acm_certificate_validation" "app" {
  count = local.sso_enabled ? 1 : 0

  certificate_arn         = aws_acm_certificate.app[0].arn
  validation_record_fqdns = [for record in aws_route53_record.app_certificate_validation : record.fqdn]
}

resource "aws_route53_record" "app_alias_a" {
  count = local.sso_enabled ? 1 : 0

  zone_id = var.existing_route53_zone_id != null ? var.existing_route53_zone_id : aws_route53_zone.app[0].zone_id
  name    = local.app_domain
  type    = "A"

  alias {
    evaluate_target_health = true
    name                   = aws_lb.app.dns_name
    zone_id                = aws_lb.app.zone_id
  }
}

resource "aws_route53_record" "app_alias_aaaa" {
  count = local.sso_enabled ? 1 : 0

  zone_id = var.existing_route53_zone_id != null ? var.existing_route53_zone_id : aws_route53_zone.app[0].zone_id
  name    = local.app_domain
  type    = "AAAA"

  alias {
    evaluate_target_health = true
    name                   = aws_lb.app.dns_name
    zone_id                = aws_lb.app.zone_id
  }
}

resource "aws_cognito_user_pool" "main" {
  count = local.sso_enabled ? 1 : 0

  name = "${local.name_prefix}-users"

  auto_verified_attributes = ["email"]
  mfa_configuration        = "ON"
  username_attributes      = ["email"]

  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    require_uppercase                = true
    temporary_password_validity_days = 7
  }

  software_token_mfa_configuration {
    enabled = true
  }

  user_pool_add_ons {
    advanced_security_mode = "ENFORCED"
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  username_configuration {
    case_sensitive = false
  }

  verification_message_template {
    default_email_option = "CONFIRM_WITH_CODE"
  }

  schema {
    attribute_data_type = "String"
    mutable             = true
    name                = "email"
    required            = true

    string_attribute_constraints {
      min_length = 5
      max_length = 320
    }
  }

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-user-pool"
  })
}

resource "aws_cognito_user_pool_client" "alb" {
  count = local.sso_enabled ? 1 : 0

  name         = "${local.name_prefix}-alb-client"
  user_pool_id = aws_cognito_user_pool.main[0].id

  access_token_validity                         = 60
  allowed_oauth_flows                           = ["code"]
  allowed_oauth_flows_user_pool_client          = true
  allowed_oauth_scopes                          = ["email", "openid", "profile"]
  auth_session_validity                         = 3
  callback_urls                                 = ["https://${local.app_domain}/oauth2/idpresponse"]
  default_redirect_uri                          = "https://${local.app_domain}/oauth2/idpresponse"
  enable_propagate_additional_user_context_data = true
  enable_token_revocation                       = true
  explicit_auth_flows                           = ["ALLOW_REFRESH_TOKEN_AUTH", "ALLOW_USER_SRP_AUTH"]
  generate_secret                               = true
  id_token_validity                             = 60
  logout_urls                                   = ["https://${local.app_domain}"]
  prevent_user_existence_errors                 = "ENABLED"
  read_attributes                               = ["email", "email_verified"]
  refresh_token_validity                        = 30
  supported_identity_providers                  = ["COGNITO"]
  write_attributes                              = ["email"]

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "main" {
  count = local.sso_enabled ? 1 : 0

  domain       = local.cognito_domain_prefix
  user_pool_id = aws_cognito_user_pool.main[0].id
}

resource "aws_cognito_identity_pool" "main" {
  count = local.sso_enabled ? 1 : 0

  identity_pool_name               = "${replace(local.name_prefix, "-", "_")}_identity"
  allow_unauthenticated_identities = false

  cognito_identity_providers {
    client_id               = aws_cognito_user_pool_client.alb[0].id
    provider_name           = "cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.main[0].id}"
    server_side_token_check = true
  }
}

data "aws_iam_policy_document" "cognito_identity_authenticated_assume_role" {
  count = local.sso_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = ["cognito-identity.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "cognito-identity.amazonaws.com:aud"
      values   = [aws_cognito_identity_pool.main[0].id]
    }

    condition {
      test     = "ForAnyValue:StringLike"
      variable = "cognito-identity.amazonaws.com:amr"
      values   = ["authenticated"]
    }
  }
}

resource "aws_iam_role" "cognito_authenticated" {
  count = local.sso_enabled ? 1 : 0

  name               = "${local.name_prefix}-cognito-authenticated-role"
  assume_role_policy = data.aws_iam_policy_document.cognito_identity_authenticated_assume_role[0].json

  tags = local.tags
}

resource "aws_cognito_identity_pool_roles_attachment" "main" {
  count = local.sso_enabled ? 1 : 0

  identity_pool_id = aws_cognito_identity_pool.main[0].id

  roles = {
    authenticated = aws_iam_role.cognito_authenticated[0].arn
  }
}
