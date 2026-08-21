export interface Kline {
  openTime: number; // epoch ms
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  closeTime: number; // epoch ms
}
