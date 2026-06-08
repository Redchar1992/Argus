import { Card, Descriptions, List, Progress, Tag } from 'antd';
import type { Investigation } from '../types/investigation';

const DECISION_COLOR: Record<string, string> = {
  CLEAR: 'green',
  REVIEW: 'orange',
  BLOCK: 'red',
};

const BAND_COLOR: Record<string, string> = {
  MINIMAL: 'green',
  LOW: 'lime',
  MEDIUM: 'orange',
  HIGH: 'red',
};

export function DecisionPanel({ inv }: { inv: Investigation }) {
  if (inv.status !== 'COMPLETED') return null;
  const score = inv.riskScore ?? 0;
  return (
    <Card title="Compliance Decision" style={{ marginTop: 16 }}>
      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="Decision">
          <Tag color={DECISION_COLOR[inv.decision ?? ''] ?? 'default'} style={{ fontWeight: 600 }}>
            {inv.decision}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="Risk Band">
          <Tag color={BAND_COLOR[inv.riskBand ?? ''] ?? 'default'}>{inv.riskBand}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="Risk Score" span={2}>
          <Progress
            percent={score}
            steps={10}
            strokeColor={score >= 60 ? '#cf1322' : score >= 30 ? '#d46b08' : '#389e0d'}
          />
        </Descriptions.Item>
        <Descriptions.Item label="Decided by" span={2}>
          {inv.llmProvider}
        </Descriptions.Item>
      </Descriptions>
      <List
        style={{ marginTop: 12 }}
        size="small"
        header={<strong>Risk factors</strong>}
        bordered
        dataSource={inv.riskFactors}
        renderItem={(f) => <List.Item>{f}</List.Item>}
      />
    </Card>
  );
}
