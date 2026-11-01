import { createServer, Server } from 'http';
import { AddressInfo } from 'net';
import { TradingApiService } from './trading-api.service';

/** Ops endpoints answer 409 with a structured body on a business failure; the client must hand it over. */
describe('TradingApiService ops results', () => {
  const env = { ...process.env };
  let server: Server;
  let status: number;
  let body: unknown;
  let api: TradingApiService;

  beforeAll(async () => {
    server = createServer((_req, res) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    process.env.TRADING_URL = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    api = new TradingApiService();
  });

  afterAll(async () => {
    process.env = { ...env };
    await new Promise((resolve) => server.close(resolve));
  });

  const failed = { ok: false, message: 'position not found or not open', code: 'POSITION_NOT_FOUND', params: {} };

  it.each([
    ['closeAll', () => api.closeAll('acc1')],
    ['breakeven', () => api.breakeven(1)],
    ['moveStop', () => api.moveStop(1, 'sl', '10')],
  ])('%s returns the 409 body instead of throwing', async (_name, call) => {
    status = 409;
    body = failed;
    await expect(call()).resolves.toMatchObject({ code: 'POSITION_NOT_FOUND' });
  });

  it('still throws on a server error', async () => {
    status = 500;
    body = { error: 'boom' };
    await expect(api.closeAll('acc1')).rejects.toThrow();
  });
});
