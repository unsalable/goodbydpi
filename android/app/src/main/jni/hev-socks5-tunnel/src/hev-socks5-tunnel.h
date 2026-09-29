/*
 ============================================================================
 Name        : hev-socks5-tunnel.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
 Description : Socks5 Tunnel
 ============================================================================
 */

#ifndef __HEV_SOCKS5_TUNNEL_H__
#define __HEV_SOCKS5_TUNNEL_H__

#include "hev-list.h"

int hev_socks5_tunnel_init (int tun_fd);
void hev_socks5_tunnel_fini (void);

int hev_socks5_tunnel_run (void);
void hev_socks5_tunnel_stop (void);

void hev_socks5_tunnel_stats (size_t *tx_packets, size_t *tx_bytes,
                              size_t *rx_packets, size_t *rx_bytes);

void hev_socks5_tunnel_update_session (HevListNode *node);

/* gdpi: JNI baslatmasi oncesi, onceki calismadan kalan SYNC_STOP bayragini
 * temizler (bkz. hev-socks5-tunnel.c). */
void hev_socks5_tunnel_clear_stop (void);

#endif /* __HEV_SOCKS5_TUNNEL_H__ */
