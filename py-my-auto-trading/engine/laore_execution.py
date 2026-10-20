import logging

logger = logging.getLogger("LaoreExecution")

class LaoreExecutionTracker:
    def __init__(self, db_ref):
        self.db_ref = db_ref

    def is_order_processed(self, order_id):
        ref = self.db_ref.child("processed_orders").child(str(order_id))
        return ref.get() is not None

    def mark_order_processed(self, order_id, details):
        ref = self.db_ref.child("processed_orders").child(str(order_id))
        ref.set(details)

    def process_new_executions(self, executions, current_status):
        new_status = current_status.copy()
        for exec_data in executions:
            order_id = exec_data.get('order_id')
            if not self.is_order_processed(order_id):
                qty = exec_data.get('qty', 0)
                price = exec_data.get('price', 0)
                side = exec_data.get('side', 'buy')
                
                # 평단가 및 수량 계산
                old_qty = new_status.get('holding_quantity', 0.0)
                old_avg = new_status.get('average_price', 0.0)
                
                if side == 'buy':
                    new_qty = old_qty + qty
                    if new_qty > 0:
                        new_avg = ((old_qty * old_avg) + (qty * price)) / new_qty
                    else:
                        new_avg = 0.0
                    new_status['holding_quantity'] = new_qty
                    new_status['average_price'] = new_avg
                    new_status['remaining_cash'] = new_status.get('remaining_cash', 0.0) - (qty * price)
                elif side == 'sell':
                    new_qty = old_qty - qty
                    new_status['holding_quantity'] = max(0.0, new_qty)
                    if new_qty <= 0:
                        new_status['average_price'] = 0.0
                        new_status['current_round'] = 0 # 사이클 종료
                        new_status['t_value'] = 0.0
                    new_status['remaining_cash'] = new_status.get('remaining_cash', 0.0) + (qty * price)
                    
                self.mark_order_processed(order_id, exec_data)
                
        return new_status
