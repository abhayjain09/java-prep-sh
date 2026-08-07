resource "aws_lb" "app" {
  name               = substr("${local.name_prefix}-alb", 0, 32)
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = [for subnet in aws_subnet.public : subnet.id]

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-alb"
  })
}

resource "aws_lb_target_group" "web" {
  name        = substr("${local.name_prefix}-web", 0, 32)
  port        = 80
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  health_check {
    enabled             = true
    path                = "/"
    healthy_threshold   = 2
    unhealthy_threshold = 5
    matcher             = "200-399"
  }

  tags = local.tags
}

resource "aws_lb_target_group" "api" {
  name        = substr("${local.name_prefix}-api", 0, 32)
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  health_check {
    enabled             = true
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 5
    matcher             = "200-399"
  }

  tags = local.tags
}

resource "aws_lb_listener" "http" {
  count = local.sso_enabled ? 0 : 1

  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

resource "aws_lb_listener_rule" "api_paths_http" {
  count = local.sso_enabled ? 0 : 1

  listener_arn = aws_lb_listener.http[0].arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }

  condition {
    path_pattern {
      values = [
        "/api/*",
        "/swagger-ui*",
        "/swagger-ui.html",
        "/v3/api-docs*",
        "/actuator/*",
      ]
    }
  }
}

resource "aws_lb_listener" "http_redirect" {
  count = local.sso_enabled ? 1 : 0

  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  count = local.sso_enabled ? 1 : 0

  load_balancer_arn = aws_lb.app.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.app[0].certificate_arn

  default_action {
    type  = "authenticate-cognito"
    order = 1

    authenticate_cognito {
      on_unauthenticated_request = "authenticate"
      scope                      = "openid email profile"
      session_cookie_name        = "AWSELBAuthSessionCookie"
      session_timeout            = 604800
      user_pool_arn              = aws_cognito_user_pool.main[0].arn
      user_pool_client_id        = aws_cognito_user_pool_client.alb[0].id
      user_pool_domain           = aws_cognito_user_pool_domain.main[0].domain
    }
  }

  default_action {
    type             = "forward"
    order            = 2
    target_group_arn = aws_lb_target_group.web.arn
  }
}

resource "aws_lb_listener_rule" "api_paths_https" {
  count = local.sso_enabled ? 1 : 0

  listener_arn = aws_lb_listener.https[0].arn
  priority     = 10

  action {
    type  = "authenticate-cognito"
    order = 1

    authenticate_cognito {
      on_unauthenticated_request = "authenticate"
      scope                      = "openid email profile"
      session_cookie_name        = "AWSELBAuthSessionCookie"
      session_timeout            = 604800
      user_pool_arn              = aws_cognito_user_pool.main[0].arn
      user_pool_client_id        = aws_cognito_user_pool_client.alb[0].id
      user_pool_domain           = aws_cognito_user_pool_domain.main[0].domain
    }
  }

  action {
    type             = "forward"
    order            = 2
    target_group_arn = aws_lb_target_group.api.arn
  }

  condition {
    path_pattern {
      values = [
        "/api/*",
        "/swagger-ui*",
        "/swagger-ui.html",
        "/v3/api-docs*",
        "/actuator/*",
      ]
    }
  }
}
