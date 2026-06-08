-- Seed data mirroring the in-app Java seeders, for a Postgres-backed deployment.
-- Addresses are illustrative; none are real OFAC entries.

INSERT INTO sanctioned_address (address, entity, list_source, program, severity) VALUES
  ('0xbadc0de000000000000000000000000000000bad', 'Lazarus-linked wallet',      'OFAC-SDN',            'DPRK',      95),
  ('0x515c70000000000000000000000000000000m1xr', 'TornadoCash-style mixer',    'OFAC-SDN',            'CYBER2',    90),
  ('0x4444f1a6000000000000000000000000000scam1', 'Known fraud cash-out',       'INTERNAL-WATCHLIST',  'FRAUD',     70),
  ('0x9999dark00000000000000000000000000market', 'Darknet market hot wallet',  'EU-CONSOLIDATED',     'NARCOTICS', 85)
ON CONFLICT (address) DO NOTHING;

INSERT INTO transaction_edge (from_address, to_address, amount_usd, asset, tx_hash) VALUES
  ('0xc0ffee00000000000000000000000000000c0ffee', '0x515c70000000000000000000000000000000m1xr', 250000, 'ETH',  '0xtx001'),
  ('0xc0ffee00000000000000000000000000000c0ffee', '0xaaa1110000000000000000000000000000000aaa', 40000,  'USDT', '0xtx002'),
  ('0x515c70000000000000000000000000000000m1xr', '0xbbb2220000000000000000000000000000000bbb', 180000, 'ETH',  '0xtx003'),
  ('0xaaa1110000000000000000000000000000000aaa', '0xbbb2220000000000000000000000000000000bbb', 15000,  'USDT', '0xtx004'),
  ('0xbbb2220000000000000000000000000000000bbb', '0x9999dark00000000000000000000000000market', 60000,  'ETH',  '0xtx005'),
  ('0xdeadbeef0000000000000000000000000deadbeef', '0xd0010000000000000000000000000000000d001', 8500, 'USDT', '0xtx010'),
  ('0xdeadbeef0000000000000000000000000deadbeef', '0xd0020000000000000000000000000000000d002', 8200, 'USDT', '0xtx011'),
  ('0xdeadbeef0000000000000000000000000deadbeef', '0xd0030000000000000000000000000000000d003', 7900, 'USDT', '0xtx012'),
  ('0xdeadbeef0000000000000000000000000deadbeef', '0xd0040000000000000000000000000000000d004', 8800, 'USDT', '0xtx013'),
  ('0xdeadbeef0000000000000000000000000deadbeef', '0xd0050000000000000000000000000000000d005', 8100, 'USDT', '0xtx014'),
  ('0xdeadbeef0000000000000000000000000deadbeef', '0xd0060000000000000000000000000000000d006', 8600, 'USDT', '0xtx015'),
  ('0xd0010000000000000000000000000000000d001', '0x4444f1a6000000000000000000000000000scam1', 7000, 'USDT', '0xtx016'),
  ('0xc1ean000000000000000000000000000000c1ean', '0xexchange00000000000000000000000000binance', 5000, 'USDT', '0xtx020'),
  ('0xexchange00000000000000000000000000binance', '0xc1ean000000000000000000000000000000c1ean', 3000, 'USDT', '0xtx021'),
  ('0xc1ean000000000000000000000000000000c1ean', '0xfriend0000000000000000000000000000friend', 1200, 'ETH', '0xtx022');

INSERT INTO tool_status (tool_id, description, enabled) VALUES
  ('sanctions_screen',   'Check addresses against the sanctions/watchlist', TRUE),
  ('trace_transactions', 'Walk the transaction graph N hops to find exposure to flagged addresses', TRUE),
  ('address_profile',    'Aggregate inflow/outflow/counterparty stats for an address', TRUE),
  ('risk_rules',         'Evaluate AML threshold rules over gathered facts', TRUE)
ON CONFLICT (tool_id) DO NOTHING;

INSERT INTO screening_policy (policy_key, description, int_value) VALUES
  ('blockThreshold',  'Risk score at/above which a wallet is BLOCKED', 60),
  ('reviewThreshold', 'Risk score at/above which a wallet is sent to manual REVIEW', 30),
  ('maxAgentSteps',   'Maximum plan-act-observe iterations per investigation', 8),
  ('traceMaxHops',    'Default hop depth for the trace_transactions tool', 3)
ON CONFLICT (policy_key) DO NOTHING;
