import copy,unittest
import shard_plan as p

class ShardPlanTests(unittest.TestCase):
    def test_long_origins_are_not_stacked_by_round_robin_position(self):
        origins=['a','b','c','d'];costs={'a':100,'b':1,'c':99,'d':1};plan=p.schedule(origins,costs,2)
        self.assertLessEqual(max(sum(costs[o] for o in group) for group in plan),101)
        self.assertEqual(set(origins),{o for group in plan for o in group})
    def test_input_order_does_not_change_the_sealed_plan(self):
        origins=['a','b','c','d'];costs={'a':100,'b':1,'c':99,'d':1}
        self.assertEqual(p.schedule(origins,costs,2),p.schedule(list(reversed(origins)),costs,2))
    def test_bad_costs_and_missing_or_duplicate_origins_fail(self):
        for mutation in ('missing','extra','duplicate','zero','negative','nan','infinity','too-many-shards'):
            with self.subTest(mutation=mutation):
                origins=['a','b','c'];costs={'a':5,'b':4,'c':3};count=2
                if mutation=='missing':del costs['a']
                if mutation=='extra':costs['d']=2
                if mutation=='duplicate':origins.append('a')
                if mutation=='zero':costs['a']=0
                if mutation=='negative':costs['a']=-1
                if mutation=='nan':costs['a']=float('nan')
                if mutation=='infinity':costs['a']=float('inf')
                if mutation=='too-many-shards':count=4
                with self.assertRaises(ValueError):p.schedule(origins,costs,count)
    def test_aggregate_plan_cannot_drop_duplicate_or_add_origins(self):
        for value in ([['a'],['b']],[['a','b'],['b']],[['a','b'],['c','d']]):
            with self.subTest(value=value),self.assertRaises(ValueError):p.validate(value,['a','b','c'],2)

if __name__=='__main__':unittest.main(verbosity=2)
