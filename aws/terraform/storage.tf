resource "aws_efs_file_system" "postgres" {
  creation_token   = "${local.name_prefix}-postgres"
  performance_mode = "generalPurpose"
  throughput_mode  = "bursting"
  encrypted        = true

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-postgres-efs"
  })
}

resource "aws_efs_mount_target" "postgres" {
  count = length(aws_subnet.private)

  file_system_id  = aws_efs_file_system.postgres.id
  subnet_id       = aws_subnet.private[count.index].id
  security_groups = [aws_security_group.efs.id]
}

resource "aws_efs_access_point" "postgres" {
  file_system_id = aws_efs_file_system.postgres.id

  posix_user {
    gid = 999
    uid = 999
  }

  root_directory {
    path = "/postgres"

    creation_info {
      owner_gid   = 999
      owner_uid   = 999
      permissions = "750"
    }
  }

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-postgres-ap"
  })
}
