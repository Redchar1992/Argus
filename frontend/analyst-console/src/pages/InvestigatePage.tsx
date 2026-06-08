import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  AutoComplete,
  Button,
  Card,
  Col,
  Layout,
  Row,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { getInvestigation, submitInvestigation } from '../api/client';
import type { Investigation } from '../types/investigation';
import { AgentTimeline } from '../components/AgentTimeline';
import { DecisionPanel } from '../components/DecisionPanel';

const { Header, Content } = Layout;
const { Title, Text } = Typography;

// Pre-seeded demo wallets (match infra/screening-tools seed data).
const DEMO_WALLETS = [
  { value: '0xbadc0de000000000000000000000000000000bad', label: '0xbadc0de… — directly sanctioned (BLOCK)' },
  { value: '0xc0ffee00000000000000000000000000000c0ffee', label: '0xc0ffee… — 1-hop mixer exposure (REVIEW)' },
  { value: '0xdeadbeef0000000000000000000000000deadbeef', label: '0xdeadbeef… — structuring pattern (REVIEW)' },
  { value: '0xc1ean000000000000000000000000000000c1ean', label: '0xc1ean… — clean wallet (CLEAR)' },
];

export function InvestigatePage() {
  const [address, setAddress] = useState('');
  const [inv, setInv] = useState<Investigation | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<number | null>(null);

  useEffect(() => () => stopPolling(), []);

  function stopPolling() {
    if (pollRef.current) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }

  async function poll(id: string) {
    try {
      const data = await getInvestigation(id);
      setInv(data);
      if (data.status !== 'RUNNING') {
        stopPolling();
      }
    } catch (e) {
      setError((e as Error).message);
      stopPolling();
    }
  }

  async function onSubmit() {
    if (!address.trim()) {
      setError('Enter a wallet address to investigate.');
      return;
    }
    setError(null);
    setInv(null);
    setSubmitting(true);
    stopPolling();
    try {
      const { investigationId } = await submitInvestigation(address.trim());
      // Poll the orchestrator for the live reasoning + tool-call timeline.
      await poll(investigationId);
      pollRef.current = window.setInterval(() => poll(investigationId), 700);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  const running = inv?.status === 'RUNNING';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ fontSize: 22 }}>👁️</span>
        <Title level={3} style={{ color: '#fff', margin: 0 }}>
          Argus — Analyst Console
        </Title>
        <Text style={{ color: '#aaa', marginLeft: 'auto' }}>agentic compliance screening</Text>
      </Header>
      <Content style={{ padding: 24, maxWidth: 1100, margin: '0 auto', width: '100%' }}>
        <Card>
          <Space.Compact style={{ width: '100%' }}>
            <AutoComplete
              style={{ width: '100%' }}
              options={DEMO_WALLETS}
              value={address}
              onChange={setAddress}
              placeholder="Enter a wallet address (or pick a demo wallet)"
              filterOption={(input, option) =>
                (option?.value as string).toLowerCase().includes(input.toLowerCase())
              }
            />
            <Button type="primary" loading={submitting} onClick={onSubmit}>
              Investigate
            </Button>
          </Space.Compact>
          <div style={{ marginTop: 8 }}>
            {DEMO_WALLETS.map((w) => (
              <Tag
                key={w.value}
                style={{ cursor: 'pointer', marginBottom: 4 }}
                onClick={() => setAddress(w.value)}
              >
                {w.label}
              </Tag>
            ))}
          </div>
        </Card>

        {error && <Alert style={{ marginTop: 16 }} type="error" message={error} showIcon />}

        {inv && (
          <Row gutter={16} style={{ marginTop: 16 }}>
            <Col xs={24} md={14}>
              <Card
                title={
                  <Space>
                    <span>Agent reasoning &amp; tool-call timeline</span>
                    {running && <Spin size="small" />}
                    <Tag color={running ? 'processing' : 'success'}>{inv.status}</Tag>
                  </Space>
                }
                extra={<Text code>{inv.llmProvider}</Text>}
              >
                <Text type="secondary">Subject: </Text>
                <Text code>{inv.subjectAddress}</Text>
                <div style={{ marginTop: 16 }}>
                  <AgentTimeline steps={inv.steps} />
                </div>
              </Card>
            </Col>
            <Col xs={24} md={10}>
              <DecisionPanel inv={inv} />
              {running && (
                <Alert
                  style={{ marginTop: 16 }}
                  type="info"
                  showIcon
                  message="Investigation in progress"
                  description="The agent is planning, calling tools and observing results. This panel updates live."
                />
              )}
            </Col>
          </Row>
        )}
      </Content>
    </Layout>
  );
}
